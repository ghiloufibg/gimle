package com.gimle.module.galdr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.ControlPlaneRelayClient;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link GaldrOperatorLoop}'s polling contract, driven through a scripted {@link
 * ControlPlaneRelayClient}: each tick re-reads the full current set (so a deletion simply vanishes
 * from the next tick), a reconciler-thrown exception poisons only its own tick, a failed poll
 * recovers on the next successful one, and a resource's {@link GaldrResource#reportStatus} routes
 * back out through the same relay as a typed status put.
 */
class GaldrOperatorLoopTest {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
  private static final Duration AWAIT_DEADLINE = Duration.ofSeconds(5);

  /** One captured {@code putResourceStatus} call. */
  private record CapturedStatusPut(
      String kindName, Optional<String> tenantId, String name, String statusJson) {}

  /**
   * A relay whose read answer is swappable mid-test and whose status puts are captured -- the
   * worker-side collaborator scripted without any real control channel.
   */
  private static final class ScriptedRelay implements ControlPlaneRelayClient {
    final AtomicReference<ModuleContext.RelayResult> readAnswer;
    final List<CapturedStatusPut> statusPuts = new CopyOnWriteArrayList<>();
    final List<String> readPaths = new CopyOnWriteArrayList<>();

    ScriptedRelay(ModuleContext.RelayResult initialAnswer) {
      this.readAnswer = new AtomicReference<>(initialAnswer);
    }

    @Override
    public ModuleContext.RelayResult read(String path) {
      readPaths.add(path);
      return readAnswer.get();
    }

    @Override
    public ModuleContext.RelayResult putResourceStatus(
        String kindName, Optional<String> tenantId, String name, String statusJson) {
      statusPuts.add(new CapturedStatusPut(kindName, tenantId, name, statusJson));
      return new ModuleContext.RelayResult(200, "{}");
    }
  }

  private static ModuleContext contextOver(ScriptedRelay relay) {
    return new SimpleModuleContext(
        new ModuleId("com.gimle.test.operator", Version.parse("1.0.0")),
        new SimpleServiceRegistry(),
        new ConcurrentHashMap<>(),
        Map.of(),
        relay);
  }

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + AWAIT_DEADLINE.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out awaiting: " + what);
      }
      Thread.sleep(5);
    }
  }

  private static void awaitTicksBeyond(GaldrOperatorLoop loop, long floor)
      throws InterruptedException {
    await("completed ticks beyond " + floor, () -> loop.completedTicks() > floor);
  }

  @Test
  @Timeout(15)
  void a_tick_polls_the_kind_path_and_delivers_every_parsed_resource() throws Exception {
    String body =
        "[{\"name\":\"hello\",\"tenantId\":\"acme\",\"generation\":2,"
            + "\"spec\":{\"message\":\"Hi\",\"repeat\":3},"
            + "\"status\":{\"timesSaid\":1}},"
            + "{\"name\":\"wide\",\"generation\":1,\"spec\":{\"message\":\"Yo\"}}]";
    ScriptedRelay relay = new ScriptedRelay(new ModuleContext.RelayResult(200, body));
    AtomicReference<List<GaldrResource>> lastSeen = new AtomicReference<>(List.of());

    try (GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay), "custom.Greeting", POLL_INTERVAL, lastSeen::set)) {
      awaitTicksBeyond(loop, 0);

      List<GaldrResource> seen = lastSeen.get();
      assertEquals(2, seen.size());
      assertTrue(relay.readPaths.contains("/resources/custom.Greeting"));

      GaldrResource tenanted = seen.get(0);
      assertEquals("custom.Greeting", tenanted.kindName());
      assertEquals("hello", tenanted.name());
      assertEquals(Optional.of("acme"), tenanted.tenantId());
      assertEquals(2L, tenanted.generation());
      assertEquals("Hi", tenanted.spec().getString("message"));
      assertEquals(3, tenanted.spec().getInt("repeat"));
      assertEquals(Optional.of(Map.of("timesSaid", 1L)), tenanted.status());

      GaldrResource clusterScoped = seen.get(1);
      assertEquals(Optional.empty(), clusterScoped.tenantId());
      assertEquals(Optional.empty(), clusterScoped.status());
    }
  }

  @Test
  @Timeout(15)
  void a_deleted_resource_is_simply_absent_from_the_next_tick() throws Exception {
    String twoResources =
        "[{\"name\":\"a\",\"generation\":1,\"spec\":{}},"
            + "{\"name\":\"b\",\"generation\":1,\"spec\":{}}]";
    ScriptedRelay relay = new ScriptedRelay(new ModuleContext.RelayResult(200, twoResources));
    AtomicReference<List<GaldrResource>> lastSeen = new AtomicReference<>(List.of());

    try (GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay), "custom.Greeting", POLL_INTERVAL, lastSeen::set)) {
      await("both resources seen", () -> lastSeen.get().size() == 2);

      relay.readAnswer.set(
          new ModuleContext.RelayResult(200, "[{\"name\":\"b\",\"generation\":1,\"spec\":{}}]"));
      await("the deletion observed", () -> lastSeen.get().size() == 1);

      assertEquals("b", lastSeen.get().get(0).name());
    }
  }

  @Test
  @Timeout(15)
  void a_reconciler_thrown_exception_poisons_only_its_own_tick() throws Exception {
    ScriptedRelay relay =
        new ScriptedRelay(
            new ModuleContext.RelayResult(200, "[{\"name\":\"a\",\"generation\":1,\"spec\":{}}]"));
    AtomicInteger invocations = new AtomicInteger();

    try (GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay),
            "custom.Greeting",
            POLL_INTERVAL,
            resources -> {
              if (invocations.incrementAndGet() == 1) {
                throw new IllegalStateException("poisoned first tick");
              }
            })) {
      // A completed tick only counts after the reconciler returned normally, so any completed
      // tick at all proves the loop survived the first, throwing invocation.
      awaitTicksBeyond(loop, 0);
      assertTrue(invocations.get() >= 2);
    }
  }

  @Test
  @Timeout(15)
  void a_failed_poll_never_reaches_the_reconciler_and_recovers_on_the_next_success()
      throws Exception {
    ScriptedRelay relay =
        new ScriptedRelay(new ModuleContext.RelayResult(503, "control plane restarting"));
    AtomicInteger invocations = new AtomicInteger();

    try (GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay),
            "custom.Greeting",
            POLL_INTERVAL,
            resources -> invocations.incrementAndGet())) {
      await("a few failed polls happened", () -> relay.readPaths.size() >= 2);
      assertEquals(0, invocations.get(), "a failed poll must never reach the reconciler");
      assertEquals(0, loop.completedTicks());

      relay.readAnswer.set(new ModuleContext.RelayResult(200, "[]"));
      awaitTicksBeyond(loop, 0);
      assertTrue(invocations.get() >= 1);
    }
  }

  @Test
  @Timeout(15)
  void report_status_routes_back_through_the_relay_as_a_typed_put() throws Exception {
    String body =
        "[{\"name\":\"hello\",\"tenantId\":\"acme\",\"generation\":4,"
            + "\"spec\":{\"message\":\"Hi\"}}]";
    ScriptedRelay relay = new ScriptedRelay(new ModuleContext.RelayResult(200, body));

    try (GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay),
            "custom.Greeting",
            POLL_INTERVAL,
            resources -> {
              for (GaldrResource resource : resources) {
                java.util.LinkedHashMap<String, Object> status = new java.util.LinkedHashMap<>();
                status.put("timesSaid", 1);
                status.put("observedGeneration", resource.generation());
                ModuleContext.RelayResult result = resource.reportStatus(status);
                assertEquals(200, result.status());
              }
            })) {
      awaitTicksBeyond(loop, 0);

      CapturedStatusPut put = relay.statusPuts.get(0);
      assertEquals("custom.Greeting", put.kindName());
      assertEquals(Optional.of("acme"), put.tenantId());
      assertEquals("hello", put.name());
      assertEquals("{\"timesSaid\":1,\"observedGeneration\":4}", put.statusJson());
    }
  }

  @Test
  @Timeout(15)
  void close_stops_the_loop_promptly_even_mid_sleep() throws Exception {
    ScriptedRelay relay = new ScriptedRelay(new ModuleContext.RelayResult(200, "[]"));
    GaldrOperatorLoop loop =
        GaldrOperatorLoop.start(
            contextOver(relay), "custom.Greeting", Duration.ofMinutes(10), resources -> {});
    awaitTicksBeyond(loop, 0);

    // The loop is now asleep for ten minutes; close() must not take anywhere near that long.
    long before = System.nanoTime();
    loop.close();
    assertTrue(
        Duration.ofNanos(System.nanoTime() - before).compareTo(Duration.ofSeconds(5)) < 0,
        "close() must interrupt the sleeping loop rather than wait out its interval");

    long ticksAtClose = loop.completedTicks();
    Thread.sleep(50);
    assertEquals(ticksAtClose, loop.completedTicks(), "no tick may run after close()");
  }
}
