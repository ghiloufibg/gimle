package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.integration.Greeter;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.testkit.Await;
import com.gimle.worker.testsupport.ControllableLivenessProbe;
import com.gimle.worker.testsupport.ControllableReadinessProbe;
import com.gimle.worker.testsupport.WiredWorkerRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real, dynamically-loaded fixture modules driven through a real {@link ModuleController} whose
 * event sink feeds a real {@link WorkerRuntime} -- the same "prove the wiring, don't mock it"
 * posture as {@code gimle-module}'s own integration tests, since {@link WorkerRuntime} has no seams
 * to fake {@link ModuleController}/{@link ModuleRegistry} behind.
 */
class WorkerRuntimeTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  @BeforeEach
  void resetProbeState() {
    ControllableLivenessProbe.ALIVE.set(true);
    ControllableLivenessProbe.LAST_MDC.set(null);
    ControllableReadinessProbe.READY.set(true);
  }

  private Path buildFixtureJar(String name) {
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
            health:
              liveness: com.gimle.worker.testsupport.ControllableLivenessProbe
              readiness: com.gimle.worker.testsupport.ControllableReadinessProbe
            """
                .formatted(uniqueName))
        .build(tempDir, uniqueName + ".jar");
  }

  private WiredWorkerRuntime.Result startFixture(String name, int livenessFailureThreshold) {
    return WiredWorkerRuntime.start(
        buildFixtureJar(name),
        livenessFailureThreshold,
        Optional.empty(),
        exhaustedId -> {},
        new InstanceIdentityRegistry(),
        identity -> {});
  }

  private static long activeTransitionCount(List<LifecycleEvent> events) {
    return events.stream().filter(e -> e instanceof LifecycleEvent.Active).count();
  }

  private record IdentityFixture(
      ModuleController controller,
      ModuleId id,
      InstanceIdentity identity,
      AtomicInteger uninstallCount,
      AtomicReference<InstanceIdentity> uninstalledIdentity) {}

  /**
   * Same shape as {@link #startFixture}, but wires the 10-arg identity-aware {@link WorkerRuntime}
   * constructor with a pre-populated {@link InstanceIdentityRegistry} entry -- mirrors {@code
   * WorkerMain}'s real ordering (register the identity, then resolve/start the module), so {@link
   * WorkerRuntime#onActive} finds it on the module's first {@code Active} transition.
   */
  private IdentityFixture startFixtureWithIdentity(String name) {
    Path jar = buildFixtureJar(name);
    // The identity is registered against the artifact's own id before the module ever resolves,
    // the same ordering WorkerMain itself uses (register the identity, then resolve/start) -- so
    // this reads the id straight off the artifact rather than delegating jar-reading to
    // WiredWorkerRuntime.start, which only registers the artifact into its own internal registry.
    ModuleId id = ModuleArtifactReader.read(jar).id();
    InstanceIdentity identity = new InstanceIdentity("orders-service", 3, Optional.of("acme"));
    InstanceIdentityRegistry identityRegistry = new InstanceIdentityRegistry();
    identityRegistry.register(id, identity);
    AtomicInteger uninstallCount = new AtomicInteger();
    AtomicReference<InstanceIdentity> uninstalledIdentity = new AtomicReference<>();

    WiredWorkerRuntime.Result result =
        WiredWorkerRuntime.start(
            jar,
            99,
            Optional.empty(),
            exhaustedId -> {},
            identityRegistry,
            instanceIdentity -> {
              uninstallCount.incrementAndGet();
              uninstalledIdentity.set(instanceIdentity);
            });

    return new IdentityFixture(
        result.controller(), result.id(), identity, uninstallCount, uninstalledIdentity);
  }

  @Test
  void on_active_tags_the_scheduler_with_the_registered_instance_identity() {
    IdentityFixture f = startFixtureWithIdentity("com.gimle.fixture.identity.mdc");

    Await.until(() -> ControllableLivenessProbe.LAST_MDC.get() != null, Duration.ofSeconds(2));

    Map<String, String> tags = ControllableLivenessProbe.LAST_MDC.get();
    assertEquals("orders-service", tags.get(InstanceMdcKeys.DEPLOYMENT_NAME));
    assertEquals("3", tags.get(InstanceMdcKeys.INSTANCE_INDEX));
    assertEquals(f.id().name(), tags.get(InstanceMdcKeys.MODULE_ID));
    assertEquals(f.id().version().toString(), tags.get(InstanceMdcKeys.MODULE_VERSION));
    assertEquals("acme", tags.get(InstanceMdcKeys.TENANT_ID));
  }

  @Test
  void on_uninstalled_fires_the_close_callback_exactly_once_with_the_registered_identity() {
    IdentityFixture f = startFixtureWithIdentity("com.gimle.fixture.identity.close");

    f.controller().stop(f.id());

    assertEquals(1, f.uninstallCount().get());
    assertEquals(f.identity(), f.uninstalledIdentity().get());
  }

  @Test
  void on_active_registers_the_modules_service_and_it_is_immediately_lookupable() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.service", 2);

    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
  }

  @Test
  void repeated_liveness_failures_restart_the_module_and_it_stays_registered_and_active() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.restart", 2);
    assertEquals(1, activeTransitionCount(f.events()));

    ControllableLivenessProbe.ALIVE.set(false);
    // At least one full restart cycle (Stopping -> Uninstalled -> Resolved -> Starting -> Active)
    // must complete; how many happen before this observes one is inherently racy against the
    // probe loop's own ticking, so this only asserts "at least one", not an exact count.
    Await.until(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.Uninstalled),
        Duration.ofSeconds(10));
    ControllableLivenessProbe.ALIVE.set(true);
    Await.until(() -> activeTransitionCount(f.events()) >= 2, Duration.ofSeconds(10));

    // The core regression this test guards: WorkerRuntime#restartModule used to call
    // controller.resolve(id) right after controller.stop(id), but stop() drives the module all
    // the way to UNINSTALLED, which removes it from the registry -- so resolve() would throw
    // NoSuchElementException instead of the module coming back up. If that regression returns,
    // this assertion (and the module's continued presence at all) is what catches it.
    assertTrue(f.registry().contains(f.id()));

    // onStart's hook re-registers the Greeter service against the restarted module's fresh
    // ModuleContext -- proving the restart didn't just flip lifecycle state but actually
    // re-ran module startup.
    Await.until(() -> f.serviceRegistry().lookup(Greeter.class).isPresent(), Duration.ofSeconds(2));
    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
  }

  /** Shared plumbing for the two restart-budget tests below -- only the tracked bits differ. */
  private record BudgetFixture(
      ModuleRegistry registry,
      ModuleId id,
      List<LifecycleEvent> events,
      AtomicInteger exhaustedCount) {}

  private BudgetFixture startBudgetFixture(
      String name, int livenessFailureThreshold, Duration stableUptimeThreshold) {
    AtomicInteger exhaustedCount = new AtomicInteger();
    WiredWorkerRuntime.Result result =
        WiredWorkerRuntime.start(
            buildFixtureJar(name),
            livenessFailureThreshold,
            Optional.of(stableUptimeThreshold),
            exhaustedId -> exhaustedCount.incrementAndGet(),
            new InstanceIdentityRegistry(),
            identity -> {});
    return new BudgetFixture(result.registry(), result.id(), result.events(), exhaustedCount);
  }

  private static long uninstalledCount(List<LifecycleEvent> events) {
    return events.stream().filter(e -> e instanceof LifecycleEvent.Uninstalled).count();
  }

  /**
   * Regression test for a real bug found while building a real-cluster smoke test for this exact
   * scenario: a module that restarts cleanly every time but never passes its own liveness check
   * used to restart forever, because each successful {@code stop()}/{@code start()} cycle replaced
   * its {@link com.gimle.core.restart.RestartTracker} with a fresh one (via {@code onUninstalled}
   * removing it and {@code onActive} recreating it), so {@code attemptsInWindow} never accumulated
   * past 1 and the budget could never exhaust. A high {@code stableUptimeThreshold} keeps this
   * fixture's own {@link #scheduleModuleStabilityConfirmation} from ever firing during the test.
   */
  @Test
  void a_module_that_never_recovers_liveness_exhausts_its_restart_budget_and_is_marked_failed() {
    BudgetFixture f =
        startBudgetFixture("com.gimle.fixture.neverrecovers", 2, Duration.ofMinutes(10));
    ControllableLivenessProbe.ALIVE.set(false);

    Await.until(() -> f.exhaustedCount().get() > 0, Duration.ofSeconds(15));

    assertEquals(1, f.exhaustedCount().get());
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
    // The 6th restartModule() call finds the budget already exhausted and gives up before ever
    // attempting a 6th stop()/start() cycle -- exactly 5 Uninstalled events, not 6.
    assertEquals(5, uninstalledCount(f.events()));
  }

  /**
   * The other half of the regression above: recovering (passing liveness again) must still reset
   * the budget once the module has stayed stable for {@code stableUptimeThreshold}, so a module
   * that fails, recovers, then fails again gets a genuinely fresh budget for its second failure
   * spell rather than picking up where the first left off. A short threshold keeps this fast.
   */
  @Test
  void a_module_that_recovers_before_failing_again_gets_a_fresh_restart_budget() {
    BudgetFixture f = startBudgetFixture("com.gimle.fixture.recovers", 2, Duration.ofMillis(300));

    ControllableLivenessProbe.ALIVE.set(false);
    Await.until(() -> uninstalledCount(f.events()) >= 2, Duration.ofSeconds(10));

    ControllableLivenessProbe.ALIVE.set(true);
    // Wait for the in-flight restart to actually finish and the module to reach ACTIVE first --
    // only then does the 300ms stability clock start -- rather than betting a flat sleep covers
    // both the restart and the confirmation window, which flakes on a stalled CI box.
    Await.until(() -> f.registry().state(f.id()) == ModuleState.ACTIVE, Duration.ofSeconds(10));
    // Hold comfortably past the 300ms stability threshold so the confirmation actually fires and
    // resets the tracker before the second failure spell begins.
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    long uninstalledBeforeSecondSpell = uninstalledCount(f.events());

    ControllableLivenessProbe.ALIVE.set(false);
    Await.until(() -> f.exhaustedCount().get() > 0, Duration.ofSeconds(15));

    // Without a genuine reset, the second spell alone would exhaust after exactly 5 more
    // Uninstalled events (matching the test above) -- a fresh budget means the *total* across both
    // spells exceeds 5, since the first spell already spent some of its own budget beforehand.
    long totalUninstalled = uninstalledCount(f.events());
    assertTrue(
        totalUninstalled > 5,
        "expected a fresh budget after recovery: total Uninstalled events "
            + totalUninstalled
            + " (first spell alone already produced "
            + uninstalledBeforeSecondSpell
            + ")");
  }

  @Test
  void a_readiness_failure_marks_the_service_unready_without_stopping_the_module() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.readiness", 99);

    ControllableReadinessProbe.READY.set(false);
    Await.until(() -> f.serviceRegistry().lookup(Greeter.class).isEmpty(), Duration.ofSeconds(2));

    assertEquals(1, activeTransitionCount(f.events()));
    assertTrue(f.registry().contains(f.id()));
  }

  @Test
  void a_module_becomes_lookupable_again_when_its_readiness_probe_recovers() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.readiness.recovery", 99);

    ControllableReadinessProbe.READY.set(false);
    Await.until(() -> f.serviceRegistry().lookup(Greeter.class).isEmpty(), Duration.ofSeconds(2));

    ControllableReadinessProbe.READY.set(true);
    Await.until(() -> f.serviceRegistry().lookup(Greeter.class).isPresent(), Duration.ofSeconds(2));

    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
    assertTrue(f.registry().contains(f.id()));
  }

  /**
   * Regression test for FUNC-28/GIMLE-666: {@code health.liveness} naming a class that doesn't
   * exist (a manifest typo, in production) used to leave the instance stuck ACTIVE forever --
   * {@link WorkerRuntime#onActive}'s probe {@code instantiate} call threw straight out of the
   * {@code Active} event's own dispatch, and {@code ModuleController#emit}'s generic event-sink
   * catch swallowed it after only a log line, so {@code markActive} (already run before that catch
   * ever fired) was never undone and no {@code TransitionFailed} event was ever produced.
   */
  @Test
  void a_liveness_probe_class_that_fails_to_load_forces_the_module_to_failed_with_an_event() {
    Path jar =
        TestModuleBuilder.module(
                """
                module com.gimle.fixture.badliveness {
                }
                """)
            .withDescriptor(
                """
                name: com.gimle.fixture.badliveness
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
                health:
                  liveness: com.gimle.worker.testsupport.NoSuchLivenessProbeClass
                """)
            .build(tempDir, "bad-liveness-" + (counter++) + ".jar");

    WiredWorkerRuntime.Result f =
        WiredWorkerRuntime.start(
            jar,
            2,
            Optional.empty(),
            exhaustedId -> {},
            new InstanceIdentityRegistry(),
            identity -> {});

    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
    assertTrue(
        f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed),
        "expected a durable TransitionFailed event, got: " + f.events());
    // Exactly one Active transition happened -- the module genuinely did become active (its
    // onStart hook ran) before the probe-load failure forced it straight to FAILED; the fix must
    // not, say, retry onActive and register the service twice.
    assertEquals(1, activeTransitionCount(f.events()));
  }

  /** The happy path this fix must leave untouched: a real probe class still wires up normally. */
  @Test
  void a_liveness_probe_class_that_loads_fine_leaves_the_module_active() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.goodliveness", 2);

    assertEquals(ModuleState.ACTIVE, f.registry().state(f.id()));
    assertEquals(1, activeTransitionCount(f.events()));
    assertTrue(f.events().stream().noneMatch(e -> e instanceof LifecycleEvent.TransitionFailed));
  }

  @Test
  void stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.stopping", 99);
    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));

    // ModuleController#stop() drains and disposes in one synchronous call, so WorkerRuntime's
    // Stopping (markUnready) and Uninstalled (remove) reactions both fire before this returns --
    // either one alone is enough to make the service unreachable here.
    f.controller().stop(f.id());

    assertTrue(f.serviceRegistry().lookup(Greeter.class).isEmpty());
    assertFalse(f.registry().contains(f.id()));
  }
}
