package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.integration.Greeter;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.worker.testsupport.Await;
import com.gimle.worker.testsupport.ControllableLivenessProbe;
import com.gimle.worker.testsupport.ControllableReadinessProbe;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
  void reset_probe_state() {
    ControllableLivenessProbe.ALIVE.set(true);
    ControllableReadinessProbe.READY.set(true);
  }

  private record Fixture(
      ModuleRegistry registry,
      ModuleController controller,
      WorkerRuntime runtime,
      ServiceRegistry serviceRegistry,
      ModuleId id,
      List<LifecycleEvent> events) {}

  private Path build_fixture_jar(String name) {
    String uniqueName = name + (counter++);
    return TestModuleBuilder.module(
            """
            module %s {
            }
            """
                .formatted(uniqueName))
        .with_descriptor(
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

  private Fixture start_fixture(String name, int livenessFailureThreshold) {
    Path jar = build_fixture_jar(name);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.boot_only().layer();
    ServiceRegistry serviceRegistry = new SimpleServiceRegistry();
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();

    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          events.add(event);
          WorkerRuntime runtime = runtimeRef.get();
          if (runtime != null) {
            runtime.on_lifecycle_event(event);
          }
        };

    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            sink,
            serviceRegistry);

    WorkerRuntime runtime =
        new WorkerRuntime(
            controller,
            registry,
            serviceRegistry,
            4,
            Duration.ofMillis(20),
            Duration.ofSeconds(1),
            livenessFailureThreshold,
            exhaustedId -> {});
    runtimeRef.set(runtime);

    controller.resolve(id);
    controller.start(id);

    return new Fixture(registry, controller, runtime, serviceRegistry, id, events);
  }

  private static long active_transition_count(List<LifecycleEvent> events) {
    return events.stream().filter(e -> e instanceof LifecycleEvent.Active).count();
  }

  @Test
  void on_active_registers_the_modules_service_and_it_is_immediately_lookupable() {
    Fixture f = start_fixture("com.gimle.fixture.service", 2);

    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
  }

  @Test
  void repeated_liveness_failures_restart_the_module_and_it_stays_registered_and_active() {
    Fixture f = start_fixture("com.gimle.fixture.restart", 2);
    assertEquals(1, active_transition_count(f.events()));

    ControllableLivenessProbe.ALIVE.set(false);
    // At least one full restart cycle (Stopping -> Uninstalled -> Resolved -> Starting -> Active)
    // must complete; how many happen before this observes one is inherently racy against the
    // probe loop's own ticking, so this only asserts "at least one", not an exact count.
    Await.at_least(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.Uninstalled),
        Duration.ofSeconds(10));
    ControllableLivenessProbe.ALIVE.set(true);
    Await.at_least(() -> active_transition_count(f.events()) >= 2, Duration.ofSeconds(10));

    // The core regression this test guards: WorkerRuntime#restart_module used to call
    // controller.resolve(id) right after controller.stop(id), but stop() drives the module all
    // the way to UNINSTALLED, which removes it from the registry -- so resolve() would throw
    // NoSuchElementException instead of the module coming back up. If that regression returns,
    // this assertion (and the module's continued presence at all) is what catches it.
    assertTrue(f.registry().contains(f.id()));

    // on_start's hook re-registers the Greeter service against the restarted module's fresh
    // ModuleContext -- proving the restart didn't just flip lifecycle state but actually
    // re-ran module startup.
    Await.at_least(
        () -> f.serviceRegistry().lookup(Greeter.class).isPresent(), Duration.ofSeconds(2));
    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));
  }

  @Test
  void a_readiness_failure_marks_the_service_unready_without_stopping_the_module() {
    Fixture f = start_fixture("com.gimle.fixture.readiness", 99);

    ControllableReadinessProbe.READY.set(false);
    Await.at_least(
        () -> f.serviceRegistry().lookup(Greeter.class).isEmpty(), Duration.ofSeconds(2));

    assertEquals(1, active_transition_count(f.events()));
    assertTrue(f.registry().contains(f.id()));
  }

  @Test
  void stopping_a_module_makes_its_service_unreachable_and_removes_it_from_the_registry() {
    Fixture f = start_fixture("com.gimle.fixture.stopping", 99);
    assertEquals(
        Optional.of("hello from provider"),
        f.serviceRegistry().lookup(Greeter.class).map(Greeter::greet));

    // ModuleController#stop() drains and disposes in one synchronous call, so WorkerRuntime's
    // Stopping (mark_unready) and Uninstalled (remove) reactions both fire before this returns --
    // either one alone is enough to make the service unreachable here.
    f.controller().stop(f.id());

    assertTrue(f.serviceRegistry().lookup(Greeter.class).isEmpty());
    assertFalse(f.registry().contains(f.id()));
  }
}
