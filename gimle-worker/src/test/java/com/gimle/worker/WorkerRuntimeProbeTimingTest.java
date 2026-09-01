package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.module.integration.Greeter;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.testkit.Await;
import com.gimle.worker.testsupport.ControllableLivenessProbe;
import com.gimle.worker.testsupport.SlowReadinessProbe;
import com.gimle.worker.testsupport.WiredWorkerRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves a module's own {@code health.intervalSeconds}/{@code timeoutSeconds}/{@code
 * failureThreshold} beat the worker-wide defaults it would otherwise share with every other module
 * on the same worker, and that a module declaring none of them still gets exactly those defaults.
 * Real dynamically-loaded fixture modules throughout, the same posture {@code WorkerRuntimeTest}
 * takes -- the point is the manifest reaching the live probe loop, which a faked descriptor would
 * not prove.
 */
class WorkerRuntimeProbeTimingTest {

  /**
   * Deliberately far shorter than the checks below take, so a module that does <em>not</em>
   * override it visibly fails where an overriding one passes.
   */
  private static final Duration WORKER_DEFAULT_TIMEOUT = Duration.ofMillis(100);

  private static final Duration WORKER_DEFAULT_INTERVAL = Duration.ofMillis(20);

  /**
   * High enough that no test here can reach it by accident at the 20ms default interval, so any
   * restart observed below can only have come from a module's own declared threshold.
   */
  private static final int WORKER_DEFAULT_FAILURE_THRESHOLD = 1000;

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  @BeforeEach
  void resetProbeState() {
    ControllableLivenessProbe.ALIVE.set(true);
    SlowReadinessProbe.DELAY_MILLIS.set(0);
  }

  private Path buildFixtureJar(String name, String healthBlock) {
    String uniqueName = name + (counter++);
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(uniqueName))
        .withDescriptor(
            """
            name: %s
            version: 1.0.0
            isolation:
              tier: TIER_1
            resources:
              request:
                memory: 16Mi
                cpu: 10m
              limit:
                memory: 32Mi
                cpu: 50m
            lifecycle:
              hooks: com.gimle.module.integration.ServiceProviderHooks
            %s
            """
                .formatted(uniqueName, healthBlock.stripTrailing()))
        .build(tempDir, uniqueName + ".jar");
  }

  private WiredWorkerRuntime.Result start(String name, String healthBlock) {
    return WiredWorkerRuntime.start(
        buildFixtureJar(name, healthBlock),
        WORKER_DEFAULT_INTERVAL,
        WORKER_DEFAULT_TIMEOUT,
        WORKER_DEFAULT_FAILURE_THRESHOLD,
        Optional.of(Duration.ofMinutes(10)),
        exhaustedId -> {},
        new InstanceIdentityRegistry(),
        identity -> {});
  }

  private static long uninstalledCount(List<LifecycleEvent> events) {
    return events.stream().filter(e -> e instanceof LifecycleEvent.Uninstalled).count();
  }

  /**
   * Gives the probe loop far more ticks than it needs to act, so "nothing happened" below means the
   * declared timing genuinely suppressed the action rather than the assertion merely running early.
   */
  private static void letSeveralProbeIntervalsElapse() {
    try {
      Thread.sleep(600);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void a_module_declaring_its_own_timeout_stays_ready_through_a_check_the_default_would_cut_off() {
    SlowReadinessProbe.DELAY_MILLIS.set(400);
    WiredWorkerRuntime.Result f =
        start(
            "com.gimle.fixture.timing.slowok",
            """
            health:
              readiness: com.gimle.worker.testsupport.SlowReadinessProbe
              timeoutSeconds: 5
            """);

    letSeveralProbeIntervalsElapse();

    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
  }

  @Test
  void the_same_slow_check_without_a_declared_timeout_is_cut_off_by_the_worker_default() {
    SlowReadinessProbe.DELAY_MILLIS.set(400);
    WiredWorkerRuntime.Result f =
        start(
            "com.gimle.fixture.timing.slowcutoff",
            """
            health:
              readiness: com.gimle.worker.testsupport.SlowReadinessProbe
            """);

    Await.until(() -> f.serviceRegistry().lookup(Greeter.class).isEmpty(), Duration.ofSeconds(5));
  }

  @Test
  void a_module_declaring_its_own_failure_threshold_restarts_on_that_many_liveness_failures() {
    WiredWorkerRuntime.Result f =
        start(
            "com.gimle.fixture.timing.threshold",
            """
            health:
              liveness: com.gimle.worker.testsupport.ControllableLivenessProbe
              failureThreshold: 2
            """);

    ControllableLivenessProbe.ALIVE.set(false);

    Await.until(() -> uninstalledCount(f.events()) >= 1, Duration.ofSeconds(10));
  }

  @Test
  void a_module_declaring_no_failure_threshold_keeps_the_workers_far_higher_default() {
    WiredWorkerRuntime.Result f =
        start(
            "com.gimle.fixture.timing.defaultthreshold",
            """
            health:
              liveness: com.gimle.worker.testsupport.ControllableLivenessProbe
            """);

    ControllableLivenessProbe.ALIVE.set(false);
    letSeveralProbeIntervalsElapse();

    assertEquals(0, uninstalledCount(f.events()));
    assertTrue(f.registry().contains(f.id()));
  }

  /**
   * A declared interval also pushes out the first tick, since {@code initialDelaySeconds} defaults
   * to one interval -- so a minute-long interval means no liveness verdict at all inside this
   * test's own window, even with the probe answering false the whole time.
   */
  @Test
  void a_module_declaring_a_long_interval_is_not_probed_at_the_workers_own_fast_cadence() {
    WiredWorkerRuntime.Result f =
        start(
            "com.gimle.fixture.timing.longinterval",
            """
            health:
              liveness: com.gimle.worker.testsupport.ControllableLivenessProbe
              intervalSeconds: 60
              failureThreshold: 1
            """);

    ControllableLivenessProbe.ALIVE.set(false);
    letSeveralProbeIntervalsElapse();

    assertEquals(0, uninstalledCount(f.events()));
  }
}
