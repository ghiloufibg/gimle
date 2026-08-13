package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.worker.testsupport.Await;
import com.gimle.worker.testsupport.RecordingJobHooks;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real, dynamically-loaded {@code lifecycle.jobHooks} fixture modules driven through a real {@link
 * ModuleController}/{@link WorkerRuntime} pair -- the run-to-completion sibling of {@link
 * WorkerRuntimeTest}'s {@code lifecycle.hooks} coverage, proving {@link WorkerRuntime#onActive}'s
 * {@code jobHooksClass} branch actually invokes {@link com.gimle.module.lifecycle.JobHooks#run} on
 * its own thread and drives the result back through {@link ModuleController#complete}.
 */
class JobHooksExecutionTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  @BeforeEach
  void resetHookState() {
    RecordingJobHooks.RAN.set(false);
    RecordingJobHooks.STATUS_TO_RETURN.set(CompletionStatus.SUCCEEDED);
    RecordingJobHooks.THROW_INSTEAD.set(null);
  }

  private record Fixture(
      ModuleRegistry registry,
      ModuleController controller,
      ModuleId id,
      List<LifecycleEvent> events) {}

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
              jobHooks: com.gimle.worker.testsupport.RecordingJobHooks
            """
                .formatted(uniqueName))
        .build(tempDir, uniqueName + ".jar");
  }

  private Fixture startFixture(String name) {
    Path jar = buildFixtureJar(name);
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ServiceRegistry serviceRegistry = new SimpleServiceRegistry();
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();

    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          events.add(event);
          WorkerRuntime runtime = runtimeRef.get();
          if (runtime != null) {
            runtime.onLifecycleEvent(event);
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
            99,
            exhaustedId -> {});
    runtimeRef.set(runtime);

    controller.resolve(id);
    controller.start(id);

    return new Fixture(registry, controller, id, events);
  }

  @Test
  void a_succeeding_job_runs_its_hooks_and_reaches_completed() {
    Fixture f = startFixture("com.gimle.fixture.job.succeeds");

    // registry.markCompleted() (what the second Await below would see) runs before the sink
    // publishes the Completed event, so waiting on the event itself is the only race-free signal.
    Await.atLeast(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.Completed),
        Duration.ofSeconds(2));

    assertTrue(RecordingJobHooks.RAN.get());
    assertEquals(ModuleState.COMPLETED, f.registry().state(f.id()));
    // Still registered, unlike stop()'s UNINSTALLED drain -- complete() leaves the module in place
    // so the control plane can still read its terminal state.
    assertTrue(f.registry().contains(f.id()));
  }

  @Test
  void a_failing_job_reaches_failed() {
    Fixture f = startFixture("com.gimle.fixture.job.fails");
    RecordingJobHooks.STATUS_TO_RETURN.set(CompletionStatus.FAILED);

    Await.atLeast(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed),
        Duration.ofSeconds(2));

    assertTrue(RecordingJobHooks.RAN.get());
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
  }

  @Test
  void a_job_hooks_run_that_throws_is_treated_as_failed() {
    Fixture f = startFixture("com.gimle.fixture.job.throws");
    RecordingJobHooks.THROW_INSTEAD.set(new IllegalStateException("boom"));

    Await.atLeast(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed),
        Duration.ofSeconds(2));

    assertTrue(RecordingJobHooks.RAN.get());
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
  }
}
