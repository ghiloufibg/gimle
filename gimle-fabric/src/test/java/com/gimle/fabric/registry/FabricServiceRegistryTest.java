package com.gimle.fabric.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleClusterException;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.catalog.ServiceEndpoint;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.fabric.transport.ModuleWorkExecutor;
import com.gimle.fabric.transport.RemoteInvocationException;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// Reads gimle.transport.protocol (through FabricClient/FabricServer) without ever setting it: a
// READ lock lets these plaintext classes run concurrently with each other while excluding any
// class that mutates the JVM-global TLS properties mid-run (see FabricTransportTlsTest).
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
class FabricServiceRegistryTest {

  private static final ServiceExport GREETER_EXPORT =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));
  private static final ModuleInstanceId OWNER =
      ModuleInstanceId.unattached(
          new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));

  private final MemberId selfNode =
      new MemberId("node-a", new InetSocketAddress("127.0.0.1", 7946));
  private final List<FabricServer> servers = new ArrayList<>();
  private final List<ServerSocketChannel> hangUpListeners = new ArrayList<>();

  @AfterEach
  void tearDown() throws IOException {
    servers.forEach(FabricServer::close);
    for (ServerSocketChannel listener : hangUpListeners) {
      listener.close();
    }
  }

  /**
   * An endpoint that accepts and immediately hangs up, so the caller's failure lands after the
   * request was written rather than before the connection existed. That matters here because a
   * connect-time failure is retried against another endpoint automatically -- which would mask the
   * breaker behavior these tests are about by making the call succeed anyway.
   */
  private InetSocketAddress startHangUpEndpoint() throws IOException {
    ServerSocketChannel listener = ServerSocketChannel.open();
    listener.bind(new InetSocketAddress("127.0.0.1", 0));
    hangUpListeners.add(listener);
    Thread.ofVirtual()
        .start(
            () -> {
              while (listener.isOpen()) {
                try {
                  listener.accept().close();
                } catch (IOException e) {
                  return; // teardown closed the listener; nothing to report
                }
              }
            });
    return (InetSocketAddress) listener.getLocalAddress();
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

  /**
   * A backend that reports a fixed inbound backlog on every response, standing in for a replica
   * kept busy by callers other than this one -- which is the load this registry cannot see any
   * other way, since its own outstanding-request count against such a replica is zero.
   */
  private InetSocketAddress startBackendReporting(Greeter impl, int queueDepth) throws IOException {
    SimpleServiceRegistry backing = new SimpleServiceRegistry();
    backing.register(OWNER, Greeter.class, impl);
    ModuleWorkExecutor executor =
        new ModuleWorkExecutor() {
          @Override
          public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> ran = new FutureTask<>(task);
            ran.run();
            return ran;
          }

          @Override
          public int queueDepth() {
            return queueDepth;
          }
        };
    FabricServer server =
        new FabricServer(
            backing,
            Greeter.class.getClassLoader(),
            id -> Optional.empty(),
            id -> Optional.of(executor));
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

  /**
   * The saturation this caller cannot see for itself: the same-machine replica is loaded by other
   * callers, so this registry's own outstanding count against it is zero and it looks perfectly
   * idle. Balancing on that alone kept sending every request into the busy replica until calls
   * started failing and its breaker tripped -- the caller saw errors where a handoff to the idle
   * remote replica would have been invisible.
   */
  @Test
  @Timeout(15)
  void a_replica_saturated_by_other_callers_loses_to_an_idle_remote_one() throws Exception {
    InetSocketAddress busySameMachine = startBackendReporting(name -> "same-machine:" + name, 32);
    InetSocketAddress idleRemote = startBackendReporting(name -> "remote:" + name, 0);

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-other", OWNER, GREETER_EXPORT, Optional.empty(), busySameMachine);
    catalog.localRegister(
        new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947)),
        "worker-b",
        OWNER,
        GREETER_EXPORT,
        Optional.empty(),
        idleRemote);

    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);
    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();

    // The first call has no report to go on and takes the same-machine preference, which is what
    // teaches this registry how loaded that replica is. Every call after it must route around it.
    assertEquals("same-machine:first", greeter.greet("first"));
    assertEquals("remote:second", greeter.greet("second"));
    assertEquals("remote:third", greeter.greet("third"));
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
    // A same-machine endpoint that accepts and hangs up: raw outstanding-request count alone makes
    // it look like the least-loaded same-machine candidate (every call fails instantly, so its
    // outstanding count is always back near zero) -- exactly the failure mode this test guards
    // against, since a healthy remote endpoint sits idle the whole time. Hanging up mid-call
    // rather than refusing the connection keeps the failure one the caller may not retry, so what
    // is asserted below is genuinely the breaker steering selection, not a failover masking it.
    InetSocketAddress deadSameMachineAddress = startHangUpEndpoint();
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

  /**
   * An exception carrying a live handle no serializer can write -- the shape a real service
   * exception takes when it captures something from its own runtime rather than plain data.
   */
  private static final class LiveHandleFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Object handle;

    LiveHandleFailure(String message, Object handle) {
      super(message);
      this.handle = handle;
    }

    Object handle() {
      return handle;
    }
  }

  @Test
  @Timeout(15)
  void a_target_exception_that_cannot_be_serialized_reaches_the_caller_named() throws Exception {
    // The target answered perfectly clearly -- it threw, with a message. Nothing about that is a
    // transport problem, so the caller must never be told it was one.
    InetSocketAddress address =
        startBackend(
            name -> {
              throw new LiveHandleFailure("unknown customer: " + name, new Object());
            });
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-throwing", OWNER, GREETER_EXPORT, Optional.empty(), address);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    RemoteInvocationException thrown =
        assertThrows(RemoteInvocationException.class, () -> greeter.greet("x"));

    assertEquals(LiveHandleFailure.class.getName(), thrown.remoteTypeName());
    assertEquals(Optional.of("unknown customer: x"), thrown.remoteMessage());
  }

  @Test
  @Timeout(15)
  void a_target_exception_with_a_null_message_that_cannot_be_serialized_reports_no_message()
      throws Exception {
    InetSocketAddress address =
        startBackend(
            name -> {
              throw new LiveHandleFailure(null, new Object());
            });
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        selfNode, "worker-throwing", OWNER, GREETER_EXPORT, Optional.empty(), address);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
    RemoteInvocationException thrown =
        assertThrows(RemoteInvocationException.class, () -> greeter.greet("x"));

    assertEquals(LiveHandleFailure.class.getName(), thrown.remoteTypeName());
    assertEquals(Optional.empty(), thrown.remoteMessage());
    assertTrue(thrown.getMessage().contains("(no message)"));
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

  // ---- cross-worker version-aware cutover (mirrors SimpleServiceRegistry#selectEntry's
  // same-worker cutover during a hot redeploy) ----

  private static final ServiceExport GREETER_EXPORT_V1 =
      new ServiceExport(Greeter.class.getName(), Version.parse("1.0.0"));
  private static final ServiceExport GREETER_EXPORT_V2 =
      new ServiceExport(Greeter.class.getName(), Version.parse("2.0.0"));

  @Test
  @Timeout(15)
  void only_the_highest_version_endpoints_are_selected_while_both_versions_are_available()
      throws Exception {
    InetSocketAddress oldVersionAddress = startBackend(name -> "old:" + name);
    InetSocketAddress newVersionAddress = startBackend(name -> "new:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    MemberId nodeB = new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));
    catalog.localRegister(
        nodeB, "worker-old", OWNER, GREETER_EXPORT_V1, Optional.empty(), oldVersionAddress);
    catalog.localRegister(
        nodeB, "worker-new", OWNER, GREETER_EXPORT_V2, Optional.empty(), newVersionAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Both versions' endpoints are healthy and idle throughout -- a blended round-robin across
    // both (the pre-fix behavior) would sometimes return "old:x"; every lookup must land on the
    // highest version only.
    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("new:x", greeter.greet("x"));
    }
  }

  @Test
  @Timeout(15)
  void
      lookup_falls_back_to_the_next_highest_version_once_the_top_versions_sole_endpoint_is_breaker_excluded()
          throws Exception {
    InetSocketAddress oldVersionHealthyAddress = startBackend(name -> "old:" + name);
    InetSocketAddress newVersionDeadAddress =
        new InetSocketAddress("127.0.0.1", 1); // nothing listens here

    ServiceCatalog catalog = new ServiceCatalog();
    MemberId nodeB = new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));
    catalog.localRegister(
        nodeB, "worker-old", OWNER, GREETER_EXPORT_V1, Optional.empty(), oldVersionHealthyAddress);
    catalog.localRegister(
        nodeB, "worker-new", OWNER, GREETER_EXPORT_V2, Optional.empty(), newVersionDeadAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Same polling pattern as a_failing_endpoints_breaker_opens_and_is_excluded above: the top
    // version (2.0.0) is the only one initially selected and its sole endpoint is dead, so early
    // lookups fail; once its breaker opens, every further lookup must fall back to the next
    // highest version (1.0.0) instead of returning nothing.
    int consecutiveFallback = 0;
    int attempts = 0;
    while (consecutiveFallback < 5 && attempts < 500) {
      attempts++;
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      String result;
      try {
        result = greeter.greet("x");
      } catch (RuntimeException e) {
        result = "failed";
      }
      consecutiveFallback = "old:x".equals(result) ? consecutiveFallback + 1 : 0;
    }
    assertTrue(
        consecutiveFallback >= 5,
        "never stabilized on the fallback (1.0.0) endpoint within " + attempts + " attempts");

    // Once stabilized, every further lookup must keep landing on the fallback version -- never
    // routing to the dead top-version endpoint again.
    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("old:x", greeter.greet("x"));
    }
  }

  @Test
  @Timeout(10)
  void a_single_version_export_round_robins_normally_and_is_unaffected_by_version_narrowing()
      throws IOException {
    InetSocketAddress addressA = startBackend(name -> "a:" + name);
    InetSocketAddress addressB = startBackend(name -> "b:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(selfNode, "worker-a", OWNER, GREETER_EXPORT, Optional.empty(), addressA);
    catalog.localRegister(selfNode, "worker-b", OWNER, GREETER_EXPORT, Optional.empty(), addressB);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Only one version exists, so the version-cutover step must be a no-op: both replicas remain
    // reachable via ordinary least-outstanding-requests selection, exactly like before this fix.
    Set<String> observed = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      observed.add(greeter.greet("x"));
    }
    assertEquals(Set.of("a:x", "b:x"), observed);
  }

  @Test
  @Timeout(10)
  void
      locality_preference_still_applies_within_the_version_narrowed_pool_and_ignores_a_stale_older_version()
          throws IOException {
    InetSocketAddress staleOldVersionRemoteAddress = startBackend(name -> "stale-old:" + name);
    InetSocketAddress newVersionSameMachineAddress = startBackend(name -> "same-machine:" + name);
    InetSocketAddress newVersionRemoteAddress = startBackend(name -> "remote:" + name);

    ServiceCatalog catalog = new ServiceCatalog();
    MemberId nodeB = new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));
    // A stale 1.0.0 endpoint, remote -- must never be selected once 2.0.0 is present anywhere.
    catalog.localRegister(
        nodeB,
        "worker-stale",
        OWNER,
        GREETER_EXPORT_V1,
        Optional.empty(),
        staleOldVersionRemoteAddress);
    // The current 2.0.0 version, available both same-machine and remote.
    catalog.localRegister(
        selfNode,
        "worker-other",
        OWNER,
        GREETER_EXPORT_V2,
        Optional.empty(),
        newVersionSameMachineAddress);
    catalog.localRegister(
        nodeB, "worker-new", OWNER, GREETER_EXPORT_V2, Optional.empty(), newVersionRemoteAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    // Steady state (each call completes instantly, so outstanding counts stay tied at 0/0):
    // same-machine must still be preferred over remote within the narrowed (2.0.0-only) pool, and
    // the stale 1.0.0 remote endpoint must never be reached.
    for (int i = 0; i < 5; i++) {
      Greeter greeter = registry.lookup(Greeter.class).orElseThrow();
      assertEquals("same-machine:x", greeter.greet("x"));
    }
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

  // ---- redeploy: a disposed instance's endpoint must be actively pruned, not merely left for a
  // circuit breaker to eventually notice ----

  @Test
  @Timeout(10)
  void redeploying_a_service_drops_the_disposed_instances_endpoint_from_every_later_lookup()
      throws IOException {
    InetSocketAddress oldAddress = startBackend(name -> "old:" + name);
    InetSocketAddress newAddress = startBackend(name -> "new:" + name);

    ModuleInstanceId oldOwner =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.0")));
    ModuleInstanceId newOwner =
        ModuleInstanceId.unattached(
            new ModuleId("com.gimle.example.greeter", Version.parse("1.0.1")));
    MemberId nodeB = new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));
    ServiceCatalog catalog = new ServiceCatalog();
    catalog.localRegister(
        nodeB, "worker-old", oldOwner, GREETER_EXPORT, Optional.empty(), oldAddress);
    FabricServiceRegistry registry = newRegistry(new SimpleServiceRegistry(), catalog);

    assertEquals("old:x", registry.lookup(Greeter.class).orElseThrow().greet("x"));

    // Redeploy: the old instance is disposed -- exactly the sequence a real rolling update
    // produces (the old worker's own ServiceUnregistered reaching this catalog before the
    // replacement worker's registration) -- before the new instance ever registers.
    catalog.localUnregister(nodeB, "worker-old", oldOwner, GREETER_EXPORT);
    catalog.localRegister(
        nodeB, "worker-new", newOwner, GREETER_EXPORT, Optional.empty(), newAddress);

    // The registry's own candidate list for this service must no longer include the disposed
    // instance's endpoint at all -- not merely route around it once its circuit breaker happens to
    // notice repeated failures and open.
    List<ServiceEndpoint> candidates = catalog.endpointsForInterface(Greeter.class.getName());
    assertEquals(1, candidates.size());
    assertEquals("worker-new", candidates.get(0).workerId());

    // Every subsequent lookup must land on the replacement instance, never the disposed one.
    for (int i = 0; i < 10; i++) {
      assertEquals("new:x", registry.lookup(Greeter.class).orElseThrow().greet("x"));
    }
  }

  @Test
  @Timeout(10)
  void repeated_redeploys_of_the_same_deployment_never_accumulate_stale_candidates()
      throws IOException {
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId nodeB = new MemberId("node-b", new InetSocketAddress("127.0.0.1", 7947));
    String priorWorkerId = null;
    // Four successive redeploys of the same logical service, each disposing the previous
    // instance's endpoint before registering the next -- reproduces the "juggling four stale
    // candidate endpoints for one logical service" shape a registry that never actively prunes
    // would exhibit.
    for (int generation = 0; generation < 4; generation++) {
      String workerId = "worker-gen" + generation;
      ModuleInstanceId owner =
          ModuleInstanceId.unattached(
              new ModuleId("com.gimle.example.greeter", Version.parse("1.0." + generation)));
      if (priorWorkerId != null) {
        catalog.localUnregister(
            nodeB,
            priorWorkerId,
            ModuleInstanceId.unattached(
                new ModuleId(
                    "com.gimle.example.greeter", Version.parse("1.0." + (generation - 1)))),
            GREETER_EXPORT);
      }
      catalog.localRegister(
          nodeB, workerId, owner, GREETER_EXPORT, Optional.empty(), new InetSocketAddress(0));
      priorWorkerId = workerId;

      List<ServiceEndpoint> candidates = catalog.endpointsForInterface(Greeter.class.getName());
      assertEquals(
          1,
          candidates.size(),
          "generation " + generation + " left stale candidates behind: " + candidates);
      assertEquals(workerId, candidates.get(0).workerId());
    }
  }
}
