package com.gimle.fabric.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class FabricServiceRegistryTest {

  private static final ServiceExport GREETER_EXPORT =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));
  private static final ModuleId OWNER =
      new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0"));

  private final MemberId selfNode =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));
  private final List<FabricServer> servers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    servers.forEach(FabricServer::close);
  }

  private FabricServiceRegistry newRegistry(ServiceRegistry localRegistry, ServiceCatalog catalog) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        localRegistry,
        catalog,
        owner -> List.of(GREETER_EXPORT),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200));
  }

  /** Starts a real {@link FabricServer} hosting {@code impl} and returns its bound TCP address. */
  private InetSocketAddress startBackend(Greeter impl) throws IOException {
    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, impl);
    FabricServer server = new FabricServer(backing, Greeter.class.getClassLoader());
    servers.add(server);
    return (InetSocketAddress) server.listen(new InetSocketAddress("127.0.0.1", 0));
  }

  @Test
  @Timeout(10)
  void same_worker_tier_wins_over_same_machine_and_remote() throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    InetSocketAddress sameMachineAddress = startBackend(name -> "same-machine:" + name);
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    catalog.localRegister(
        selfNode, "worker-other", OWNER, GREETER_EXPORT, Optional.empty(), sameMachineAddress);
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);

    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    localRegistry.register(OWNER, Greeter.class, name -> "same-worker:" + name);
    FabricServiceRegistry registry = newRegistry(localRegistry, catalog);

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("same-worker:x", greeter.greet("x"));
  }

  @Test
  @Timeout(10)
  void same_machine_tier_wins_over_remote_when_both_are_idle() throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    InetSocketAddress sameMachineAddress = startBackend(name -> "same-machine:" + name);
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    catalog.localRegister(
        selfNode, "worker-other", OWNER, GREETER_EXPORT, Optional.empty(), sameMachineAddress);
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);

    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Each call here is synchronous and instant, so outstanding count is back to 0/0 (tied)
    // before the next lookup -- this establishes the steady-state preference, not a claim that
    // same-machine wins regardless of load (see the spillover test below for that case).
    for (int i = 0; i < 5; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("same-machine:x", greeter.greet("x"));
    }
  }

  @Test
  @Timeout(15)
  void same_machine_tier_spills_over_to_remote_once_saturated() throws Exception {
    CountDownLatch releaseSameMachineCall = new CountDownLatch(1);
    CountDownLatch sameMachineCallStarted = new CountDownLatch(1);
    InetSocketAddress sameMachineAddress =
        startBackend(
            name -> {
              sameMachineCallStarted.countDown();
              try {
                releaseSameMachineCall.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "same-machine:" + name;
            });
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-other", OWNER, GREETER_EXPORT, Optional.empty(), sameMachineAddress);
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);

    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Thread busySameMachineCaller =
        Thread.ofVirtual()
            .start(() -> registry.lookup(Greeter.class).orElseThrow().greet("blocked"));
    sameMachineCallStarted.await(5, TimeUnit.SECONDS);

    try {
      // same-machine now has 1 outstanding call, remote has 0 -- a hard same-machine-only cutoff
      // would keep routing every subsequent lookup onto the already-busy replica forever, even
      // with an idle remote replica available; capacity-aware spillover must route here instead.
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("remote:x", greeter.greet("x"));
    } finally {
      releaseSameMachineCall.countDown();
      busySameMachineCaller.join(5000);
    }
  }

  @Test
  @Timeout(15)
  void least_outstanding_requests_prefers_the_idle_endpoint() throws Exception {
    CountDownLatch releaseSlowCall = new CountDownLatch(1);
    CountDownLatch slowCallStarted = new CountDownLatch(1);
    InetSocketAddress slowAddress =
        startBackend(
            name -> {
              slowCallStarted.countDown();
              try {
                releaseSlowCall.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return "slow:" + name;
            });
    InetSocketAddress fastAddress = startBackend(name -> "fast:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    // "worker-a-fast" sorts before "worker-b-slow" so ServiceCatalog's deterministic ordering
    // makes round-robin tie-breaking (both start at outstanding=0) predictable here.
    catalog.localRegister(
        selfNode, "worker-a-fast", OWNER, GREETER_EXPORT, Optional.empty(), fastAddress);
    catalog.localRegister(
        selfNode, "worker-b-slow", OWNER, GREETER_EXPORT, Optional.empty(), slowAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Call #1: tied at 0/0, round-robin cursor 0 -> "worker-a-fast"; completes immediately.
    assertEquals("fast:warmup", registry.lookup(Greeter.class).orElseThrow().greet("warmup"));

    // Call #2: tied again at 0/0, round-robin cursor 1 -> "worker-b-slow". Runs on its own
    // thread since it blocks until released, occupying "worker-b-slow"'s outstanding count.
    Thread slowCaller =
        Thread.ofVirtual()
            .start(() -> registry.lookup(Greeter.class).orElseThrow().greet("blocked"));
    slowCallStarted.await(5, TimeUnit.SECONDS);

    try {
      // No longer tied (fast=0, slow=1): every subsequent selection must prefer "worker-a-fast"
      // purely by outstanding count, regardless of round-robin cursor position.
      for (int i = 0; i < 3; i++) {
        Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
        assertEquals("fast:x", greeter.greet("x"));
      }
    } finally {
      releaseSlowCall.countDown();
      slowCaller.join(5000);
    }
  }

  @Test
  @Timeout(15)
  void a_failing_endpoints_breaker_opens_and_is_excluded() throws Exception {
    InetSocketAddress healthyAddress = startBackend(name -> "healthy:" + name);
    InetSocketAddress deadAddress = new InetSocketAddress("127.0.0.1", 1); // nothing listens here

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-healthy", OWNER, GREETER_EXPORT, Optional.empty(), healthyAddress);
    catalog.localRegister(
        selfNode, "worker-dead", OWNER, GREETER_EXPORT, Optional.empty(), deadAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // A fixed warmup count risks a real, previously-observed flake: how many raw attempts the
    // breaker's own error-rate window needs before it actually opens isn't guaranteed by any fixed
    // number of load-balancer picks, so a hardcoded 30 iterations occasionally wasn't enough and a
    // dead-endpoint failure leaked into the "must never fail again" loop below. Poll instead: keep
    // calling (bounded, so a genuine regression still fails deterministically) until several
    // consecutive calls in a row have all landed on the healthy endpoint, which is the actual
    // condition this test needs before it can assert "never fails again."
    Set<String> observedResults = ConcurrentHashMap.newKeySet();
    int consecutiveHealthy = 0;
    int attempts = 0;
    while (consecutiveHealthy < 5 && attempts < 500) {
      attempts++;
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      String result;
      try {
        result = greeter.greet("x");
      } catch (RuntimeException e) {
        result = "failed";
      }
      observedResults.add(result);
      consecutiveHealthy = "healthy:x".equals(result) ? consecutiveHealthy + 1 : 0;
    }
    assertTrue(observedResults.contains("healthy:x"));
    assertTrue(
        consecutiveHealthy >= 5,
        "breaker never stabilized on the healthy endpoint within " + attempts + " attempts");

    // Once the dead endpoint's breaker has opened, every further call must land on "healthy" --
    // never a failure from the dead endpoint again.
    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("healthy:x", greeter.greet("x"));
    }
  }

  @Test
  @Timeout(15)
  void an_open_breaker_on_every_same_machine_endpoint_spills_over_to_a_healthy_remote_endpoint()
      throws Exception {
    // A same-machine endpoint nothing listens on: raw outstanding-request count alone makes it
    // look like the least-loaded same-machine candidate (every call fails instantly, so its
    // outstanding count is always back near zero) -- exactly the failure mode this test guards
    // against, since a healthy remote endpoint sits idle the whole time.
    InetSocketAddress deadSameMachineAddress = new InetSocketAddress("127.0.0.1", 1);
    InetSocketAddress healthyRemoteAddress = startBackend(name -> "remote:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-dead", OWNER, GREETER_EXPORT, Optional.empty(), deadSameMachineAddress);
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        healthyRemoteAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // The sole same-machine candidate is preferred over the sole remote one while both look
    // idle, so every one of these calls lands on (and fails against) the dead endpoint --
    // deterministically driving its breaker open (windowSize=4, errorRateThreshold=0.5, all
    // failures) without ever touching the remote tier yet.
    for (int i = 0; i < 4; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertThrows(RuntimeException.class, () -> greeter.greet("x"));
    }

    // With the dead endpoint's breaker now open, every further lookup must spill over to the
    // healthy remote endpoint -- never back to the dead same-machine one, and never left routing
    // nowhere waiting for the panic-mode ejection floor.
    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("remote:x", greeter.greet("x"));
    }
  }

  @Test
  @Timeout(15)
  void an_endpoint_whose_method_throws_an_application_exception_does_not_open_its_breaker()
      throws Exception {
    // The sole endpoint's own implementation always throws -- a real answer from a reachable
    // service, not a transport failure. If the breaker mis-scored this as recordFailure() (as it
    // did before this fix), 4 calls (windowSize) at a 0.5 threshold would open it and every
    // further lookup would throw GimleClusterException("no known exporter") instead of relaying
    // the application exception.
    InetSocketAddress address =
        startBackend(
            name -> {
              throw new IllegalStateException("boom: " + name);
            });
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-throwing", OWNER, GREETER_EXPORT, Optional.empty(), address);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      RuntimeException thrown = assertThrows(RuntimeException.class, () -> greeter.greet("x"));
      assertTrue(
          thrown.getMessage().contains("boom: x"), "expected the relayed application exception");
    }
  }

  @Test
  @Timeout(15)
  void all_endpoints_failing_still_yields_a_candidate_once_the_panic_threshold_is_crossed()
      throws Exception {
    // Three endpoints, none reachable: a correlated failure that ejects every candidate for this
    // interface. Without the panic-mode ejection floor, once all three breakers open, lookup()
    // would filter every candidate out and return Optional.empty() -- routing this call nowhere
    // with no indication why, rather than at least attempting one of them.
    ServiceCatalog catalog = new ServiceCatalog();
    for (int i = 0; i < 3; i++) {
      catalog.localRegister(
          selfNode,
          "worker-dead-" + i,
          OWNER,
          GREETER_EXPORT,
          Optional.empty(),
          new InetSocketAddress("127.0.0.1", 1)); // nothing listens here
    }
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Enough failed calls (windowSize=4 per breaker, spread across 3 endpoints by round-robin
    // selection) to drive every breaker open.
    for (int i = 0; i < 30; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      try {
        greeter.greet("x");
      } catch (RuntimeException ignored) {
        // expected: nothing is listening
      }
    }

    // Every breaker is now open, but the panic floor must still yield a candidate rather than an
    // empty lookup.
    assertTrue(registry.lookup(Greeter.class).isPresent());
  }

  @Test
  void no_known_exporter_anywhere_throws_gimle_cluster_exception() {
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), new ServiceCatalog());
    assertThrows(GimleClusterException.class, () -> registry.lookup(Greeter.class));
  }

  // ---- tenant permission filtering ----

  private FabricServiceRegistry newRegistryForTenant(
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      ServiceExport export,
      Optional<String> selfTenantId) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        localRegistry,
        catalog,
        owner -> List.of(export),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200),
        selfTenantId);
  }

  private FabricServiceRegistry newRegistryForTenant(
      ServiceRegistry localRegistry,
      ServiceCatalog catalog,
      ServiceExport export,
      Optional<String> selfTenantId,
      boolean defaultDenyCrossTenant) {
    return new FabricServiceRegistry(
        selfNode,
        "worker-self",
        localRegistry,
        catalog,
        owner -> List.of(export),
        message -> {},
        Greeter.class.getClassLoader(),
        4,
        0.5,
        Duration.ofMillis(200),
        selfTenantId,
        0.5,
        defaultDenyCrossTenant);
  }

  @Test
  @Timeout(10)
  void a_caller_belonging_to_an_allowed_tenant_reaches_a_restricted_export() throws IOException {
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, restricted, Optional.of("tenant-a"));

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("remote:x", greeter.greet("x"));
  }

  @Test
  @Timeout(10)
  void a_caller_from_a_different_tenant_cannot_reach_a_restricted_export() throws IOException {
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, restricted, Optional.of("tenant-b"));

    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  @Timeout(10)
  void an_untenanted_caller_cannot_reach_a_restricted_export() throws IOException {
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(new SimpleServiceRegistry(), catalog, restricted, Optional.empty());

    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  @Timeout(10)
  void an_unrestricted_export_is_reachable_regardless_of_caller_tenant() throws IOException {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, GREETER_EXPORT, Optional.of("any-tenant"));

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("remote:x", greeter.greet("x"));
  }

  // ---- defaultDenyCrossTenant flips an unscoped export's default from public to
  // untenanted-only; a restricted export's own allow-list is unaffected either way ----

  @Test
  @Timeout(10)
  void an_untenanted_caller_still_reaches_an_unrestricted_export_with_default_deny_cross_tenant_on()
      throws IOException {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, GREETER_EXPORT, Optional.empty(), true);

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("remote:x", greeter.greet("x"));
  }

  @Test
  @Timeout(10)
  void a_tenanted_caller_cannot_reach_an_unrestricted_export_with_default_deny_cross_tenant_on()
      throws IOException {
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, GREETER_EXPORT, Optional.of("tenant-a"), true);

    assertEquals(Optional.empty(), registry.lookup(Greeter.class));
  }

  @Test
  @Timeout(10)
  void an_allow_listed_export_still_permits_its_named_tenant_with_default_deny_cross_tenant_on()
      throws IOException {
    // The flag only changes what happens when a manifest is silent about allowedTenantIds; an
    // export that already declares its own allow-list is unaffected either way.
    ServiceExport restricted =
        new ServiceExport(
            Greeter.class.getName(), Version.parse("1.0.0"), Optional.of(Set.of("tenant-a")));
    InetSocketAddress remoteAddress = startBackend(name -> "remote:" + name);
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        restricted,
        Optional.empty(),
        remoteAddress);
    FabricServiceRegistry registry =
        newRegistryForTenant(
            new SimpleServiceRegistry(), catalog, restricted, Optional.of("tenant-a"), true);

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    assertEquals("remote:x", greeter.greet("x"));
  }

  @Test
  void register_reports_the_export_over_the_control_channel() {
    List<ControlMessage> sent = new ArrayList<>();
    FabricServiceRegistry registry =
        new FabricServiceRegistry(
            selfNode,
            "worker-self",
            new SimpleServiceRegistry(),
            new ServiceCatalog(),
            owner -> List.of(GREETER_EXPORT),
            sent::add,
            Greeter.class.getClassLoader(),
            4,
            0.5,
            Duration.ofMillis(200));

    registry.register(OWNER, Greeter.class, name -> name);

    assertEquals(1, sent.size());
    assertEquals(new ControlMessage.ServiceRegistered(OWNER, GREETER_EXPORT), sent.get(0));
  }

  @Test
  void remove_reports_unregistration_for_every_export_that_was_registered() {
    List<ControlMessage> sent = new ArrayList<>();
    FabricServiceRegistry registry =
        new FabricServiceRegistry(
            selfNode,
            "worker-self",
            new SimpleServiceRegistry(),
            new ServiceCatalog(),
            owner -> List.of(GREETER_EXPORT),
            sent::add,
            Greeter.class.getClassLoader(),
            4,
            0.5,
            Duration.ofMillis(200));

    registry.register(OWNER, Greeter.class, name -> name);
    registry.remove(OWNER);

    assertEquals(2, sent.size());
    assertEquals(new ControlMessage.ServiceUnregistered(OWNER, GREETER_EXPORT), sent.get(1));
  }
}
