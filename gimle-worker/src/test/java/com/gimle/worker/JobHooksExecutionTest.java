package com.gimle.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ModuleState;
import com.gimle.module.testsupport.TestModuleBuilder;
import com.gimle.testkit.Await;
import com.gimle.worker.testsupport.RecordingJobHooks;
import com.gimle.worker.testsupport.WiredWorkerRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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

  private WiredWorkerRuntime.Result startFixture(String name) {
    return WiredWorkerRuntime.start(
        buildFixtureJar(name),
        99,
        Optional.empty(),
        exhaustedId -> {},
        new InstanceIdentityRegistry(),
        identity -> {});
  }

  @Test
  void a_succeeding_job_runs_its_hooks_and_reaches_completed() {
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.job.succeeds");

    // registry.markCompleted() (what the second Await below would see) runs before the sink
    // publishes the Completed event, so waiting on the event itself is the only race-free signal.
    Await.until(
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
    // Set before starting the fixture, not after: WorkerRuntime#onActive dispatches JobHooks#run
    // onto its own virtual thread as part of controller.start() below, which can read this field
    // before a post-start set() would ever land.
    RecordingJobHooks.STATUS_TO_RETURN.set(CompletionStatus.FAILED);
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.job.fails");

    Await.until(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed),
        Duration.ofSeconds(2));

    assertTrue(RecordingJobHooks.RAN.get());
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
  }

  @Test
  void a_succeeding_jobs_own_logging_is_mdc_tagged_for_the_application_log() {
    // WorkerRuntime#runJobHooks dispatches JobHooks#run onto a brand-new virtual thread, unlike
    // WorkerMain#runCommand's synchronous lifecycle hooks (onInstall/onStart/onStop/onUninstall),
    // which inherit the calling control-channel thread's already-tagged MDC for free. Without its
    // own explicit tagging, a Job's own run() logging (and the RecordingJobHooks.MDC_SNAPSHOT this
    // asserts against) would see an empty MDC and land in the worker's shared PLATFORM log instead
    // of this instance's own APPLICATION log.
    String name = "com.gimle.fixture.job.mdc";
    // buildFixtureJar's own uniqueName is name + (counter++) -- read counter's value before that
    // call to predict the exact ModuleId the fixture jar will register under, so an identity for
    // it can be registered before WiredWorkerRuntime.start dispatches onActive.
    ModuleId id = new ModuleId(name + counter, Version.parse("1.0.0"));
    Path jar = buildFixtureJar(name);

    InstanceIdentityRegistry identityRegistry = new InstanceIdentityRegistry();
    identityRegistry.register(
        id, new InstanceIdentity("mdc-test-deployment", 3, Optional.of("mdc-test-tenant")));

    WiredWorkerRuntime.Result f =
        WiredWorkerRuntime.start(
            jar, 99, Optional.empty(), exhaustedId -> {}, identityRegistry, identity -> {});

    Await.until(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.Completed),
        Duration.ofSeconds(2));

    Map<String, String> mdc = RecordingJobHooks.MDC_SNAPSHOT.get();
    assertEquals("mdc-test-deployment", mdc.get(InstanceMdcKeys.DEPLOYMENT_NAME));
    assertEquals("3", mdc.get(InstanceMdcKeys.INSTANCE_INDEX));
    assertEquals("mdc-test-tenant", mdc.get(InstanceMdcKeys.TENANT_ID));
    assertTrue(InstanceMdcKeys.isApplicationCategory(mdc));
  }

  @Test
  void a_job_hooks_run_that_throws_is_treated_as_failed() {
    // Set before starting the fixture, not after: WorkerRuntime#onActive dispatches JobHooks#run
    // onto its own virtual thread as part of controller.start() below, which can read this field
    // before a post-start set() would ever land.
    RecordingJobHooks.THROW_INSTEAD.set(new IllegalStateException("boom"));
    WiredWorkerRuntime.Result f = startFixture("com.gimle.fixture.job.throws");

    Await.until(
        () -> f.events().stream().anyMatch(e -> e instanceof LifecycleEvent.TransitionFailed),
        Duration.ofSeconds(2));

    assertTrue(RecordingJobHooks.RAN.get());
    assertEquals(ModuleState.FAILED, f.registry().state(f.id()));
  }
}
