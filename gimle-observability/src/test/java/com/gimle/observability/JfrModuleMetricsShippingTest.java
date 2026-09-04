package com.gimle.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end coverage for the same shipping path {@code WorkerMetricsTest}'s own {@code
 * request_latency_timer_publishes_percentiles_for_muninn_shipping} covers for request-rate/
 * latency: a real {@link ThreadNameJfrAttributor}, sharing one {@link WorkerMetrics} registry the
 * way {@code WorkerMain} now wires them together, must land a genuine CPU/allocation-named meter in
 * {@link MeterSnapshotCodec}'s own NDJSON output -- the exact payload {@code WorkerMain}'s
 * muninn-relay loop ships to Muninn. Drives real JFR sampling rather than mocking it, the same
 * "real subprocess, not mocked JFR" posture {@code RetainingPathAttributionTest} takes for leak
 * detection, except in-process since neither event here needs a recording-launch flag the way
 * {@code path-to-gc-roots} does.
 */
class JfrModuleMetricsShippingTest {

  private static final ModuleId ID =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  // Generous relative to jdk.ExecutionSample's 20ms configured period and
  // jdk.ObjectAllocationSample's own much finer default -- real sandboxes under CPU contention can
  // still take a while to schedule the busy thread at all, so this budgets for that rather than for
  // the sampling period itself.
  private static final Duration SAMPLE_BUDGET = Duration.ofSeconds(20);

  @Test
  @Timeout(30)
  void module_cpu_and_allocation_samples_ship_in_the_worker_s_muninn_snapshot() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WorkerMetrics workerMetrics = new WorkerMetrics(registry);
    // WorkerMain's own fix: the attributor shares WorkerMetrics' registry, not a throwaway one, so
    // its counters ride the exact same MeterSnapshotCodec.toNdjson(...) pass request-rate/latency
    // already do.
    try (ThreadNameJfrAttributor attributor = new ThreadNameJfrAttributor(registry)) {
      attributor.registerModule(ID);
      // Also record an ordinary request, matching what a real ACTIVE module's own inbound calls
      // would produce alongside its JFR-derived numbers -- proves the two coexist in one snapshot.
      workerMetrics.recordRequest(ID, Duration.ofMillis(12), false);

      AtomicBoolean stop = new AtomicBoolean(false);
      Thread busyThread =
          Thread.ofVirtual()
              .name("gimle-" + ID.name() + "-" + ID.version() + "-", 0)
              .start(() -> burnCpuAndAllocate(stop));
      try {
        awaitBothSamples(registry);
      } finally {
        stop.set(true);
        busyThread.join(Duration.ofSeconds(5).toMillis());
      }
      attributor.unregisterModule(ID);
    }

    String shipped = MeterSnapshotCodec.toNdjson(registry);

    assertTrue(
        shipped.contains("gimle.module.cpu.samples"),
        "expected a JFR-derived CPU metric in the shipped snapshot:\n" + shipped);
    assertTrue(
        shipped.contains("gimle.module.allocated.bytes"),
        "expected a JFR-derived allocation metric in the shipped snapshot:\n" + shipped);
    assertTrue(
        shipped.contains("gimle.module.request.count"),
        "the pre-existing request-count metric must still ship unchanged:\n" + shipped);
  }

  /** Busy CPU work interleaved with real object allocation, until {@code stop} flips. */
  private static void burnCpuAndAllocate(AtomicBoolean stop) {
    double accumulator = 0;
    java.util.List<byte[]> junk = new java.util.ArrayList<>();
    while (!stop.get()) {
      accumulator += Math.sqrt(accumulator + 1);
      junk.add(new byte[1024]);
      if (junk.size() > 10_000) {
        junk.clear();
      }
    }
  }

  private static void awaitBothSamples(SimpleMeterRegistry registry) throws InterruptedException {
    long deadline = System.nanoTime() + SAMPLE_BUDGET.toNanos();
    while (System.nanoTime() < deadline) {
      boolean haveCpu =
          registry.find("gimle.module.cpu.samples").counter() != null
              && registry.find("gimle.module.cpu.samples").counter().count() > 0;
      boolean haveAlloc =
          registry.find("gimle.module.allocated.bytes").counter() != null
              && registry.find("gimle.module.allocated.bytes").counter().count() > 0;
      if (haveCpu && haveAlloc) {
        return;
      }
      Thread.sleep(50);
    }
    fail("timed out waiting for both a CPU sample and an allocation sample to be attributed");
  }
}
