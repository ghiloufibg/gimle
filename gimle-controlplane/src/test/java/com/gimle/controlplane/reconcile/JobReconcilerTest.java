package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/** Mirrors {@link DeploymentReconcilerTest}'s own shape closely -- see that class for why. */
class JobReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.jobreconciler" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private JobSpec job(String name, Path jar, int backoffLimit, Optional<Duration> activeDeadline) {
    return new JobSpec(
        name,
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        PlacementConstraints.NONE,
        activeDeadline,
        backoffLimit,
        Optional.empty(),
        Optional.empty());
  }

  private static void registerNode(
      StateStore store, String nodeId, long freeMemoryBytes, long freeCpuMillicores) {
    store.putNodeRegistration(
        new NodeRegistration(
            nodeId, new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2))));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            nodeId,
            new ResourceUsageSnapshot(freeMemoryBytes, 0, freeCpuMillicores, 0),
            List.of()));
  }

  /**
   * Reports {@code state} for {@code run} on its own node's heartbeat -- the same mechanism a real
   * worker's own {@code observationJson} feeds into {@code AgentMain}'s heartbeat POST.
   */
  private static void reportRunState(StateStore store, JobRun run, String state) {
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            run.nodeId(),
            new ResourceUsageSnapshot(500L * 1024 * 1024, 0, 4000, 0),
            List.of(
                new InstanceObservation(
                    run.jobName(),
                    run.attempt(),
                    run.moduleId(),
                    state,
                    !"FAILED".equals(state),
                    "ACTIVE".equals(state),
                    0,
                    0,
                    0,
                    0,
                    0))));
  }

  @Test
  void places_attempt_zero_when_capacity_exists() {
    StateStore store = new StateStore(tempDir.resolve("store-basic"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new JobReconciler(store, scheduler).reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor("nightly-cleanup");
    assertEquals(1, runs.size());
    assertEquals(0, runs.get(0).attempt());
    assertEquals(Optional.empty(), store.getJobPhase("nightly-cleanup"));
  }

  @Test
  void leaves_the_job_unplaced_without_throwing_when_no_node_has_capacity() {
    StateStore store = new StateStore(tempDir.resolve("store-no-capacity"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    // no nodes registered at all

    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent: calling again doesn't error or duplicate

    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
  }

  @Test
  void a_completed_observation_marks_the_job_succeeded_and_removes_the_run() {
    StateStore store = new StateStore(tempDir.resolve("store-succeeded"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun placed = store.listJobRunsFor("nightly-cleanup").get(0);

    reportRunState(store, placed, "COMPLETED");
    reconciler.reconcileOnce();

    assertEquals(Optional.of(JobPhase.SUCCEEDED), store.getJobPhase("nightly-cleanup"));
    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
  }

  @Test
  void a_failed_observation_retries_the_next_attempt_when_backoff_budget_remains() {
    StateStore store = new StateStore(tempDir.resolve("store-retry"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor("nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler.reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor("nightly-cleanup");
    assertEquals(1, runs.size(), "the failed attempt is gone, replaced by exactly one retry");
    assertEquals(1, runs.get(0).attempt());
    assertEquals(
        Optional.empty(),
        store.getJobPhase("nightly-cleanup"),
        "still within backoffLimit -- not yet terminal");
  }

  @Test
  void exhausting_the_backoff_limit_marks_the_job_permanently_failed() {
    StateStore store = new StateStore(tempDir.resolve("store-exhausted"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 1, Optional.empty())); // backoffLimit: 1
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor("nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler.reconcileOnce();

    assertEquals(Optional.of(JobPhase.FAILED), store.getJobPhase("nightly-cleanup"));
    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
  }

  @Test
  void exceeding_the_active_deadline_marks_the_job_permanently_failed_even_mid_attempt(
      TestClock clock) {
    StateStore store = new StateStore(tempDir.resolve("store-deadline"), clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 6, Optional.of(Duration.ofMinutes(5))));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler =
        new JobReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            JobReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            JobReconciler.DEFAULT_NODE_DARK_TIMEOUT,
            clock);
    reconciler.reconcileOnce();
    assertEquals(1, store.listJobRunsFor("nightly-cleanup").size());

    clock.advance(Duration.ofMinutes(6)); // past the 5-minute deadline, run still mid-flight
    reconciler.reconcileOnce();

    assertEquals(Optional.of(JobPhase.FAILED), store.getJobPhase("nightly-cleanup"));
    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
  }

  @Test
  void a_terminal_job_is_left_alone_on_later_ticks() {
    StateStore store = new StateStore(tempDir.resolve("store-terminal"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    store.putJobPhase("nightly-cleanup", JobPhase.SUCCEEDED);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new JobReconciler(store, scheduler).reconcileOnce();

    assertTrue(
        store.listJobRunsFor("nightly-cleanup").isEmpty(),
        "a job already terminal must never get a new attempt placed");
  }

  @Test
  void deleting_a_job_removes_its_orphaned_run_on_the_next_tick() {
    StateStore store = new StateStore(tempDir.resolve("store-delete"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    new JobReconciler(store, scheduler).reconcileOnce();
    assertEquals(1, store.listJobRunsFor("nightly-cleanup").size());

    store.removeJobSpec("nightly-cleanup");
    new JobReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
    assertTrue(store.listJobRuns().isEmpty());
  }

  @Test
  void an_arbitrary_starting_snapshot_with_two_coexisting_runs_converges_to_the_highest_attempt() {
    // Simulates a crash between JobReconciler placing a retry and removing its predecessor (see
    // JobReconciler#reconcileCurrentRun's own ordering note): both attempt 0 and attempt 1 are on
    // record for the same job at once. A from-scratch reconcile must clean this down to exactly
    // one run -- the highest attempt -- without needing any history beyond this snapshot.
    StateStore store = new StateStore(tempDir.resolve("store-arbitrary"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobSpec spec = job("nightly-cleanup", jar, 3, Optional.empty());
    store.putJobSpec(spec);
    Instant now = Instant.now();
    store.putJobRun(
        new JobRun("nightly-cleanup", 0, "node-a", spec.moduleId(), spec.artifactPath(), now));
    store.putJobRun(
        new JobRun("nightly-cleanup", 1, "node-a", spec.moduleId(), spec.artifactPath(), now));

    new JobReconciler(store, scheduler).reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor("nightly-cleanup");
    assertEquals(1, runs.size(), "only the highest attempt should survive convergence");
    assertEquals(1, runs.get(0).attempt());
  }

  @Test
  void refuses_to_place_when_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    StateStore store = new StateStore(tempDir.resolve("store-hash-mismatch"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobSpec mismatched =
        new JobSpec(
            "nightly-cleanup",
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            3,
            Optional.empty(),
            Optional.of("f".repeat(64)));
    store.putJobSpec(mismatched);

    new JobReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listJobRunsFor("nightly-cleanup").isEmpty());
  }

  @Test
  void a_run_on_a_dark_but_not_yet_timed_out_node_is_not_relocated(TestClock clock) {
    StateStore store = new StateStore(tempDir.resolve("store-dark-grace"), clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    JobReconciler reconciler =
        new JobReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor("nightly-cleanup").get(0);
    reportRunState(store, attempt0, "ACTIVE");

    // node-a stops heartbeating (a partition, not a real failure): past nodeDarkTimeout, so it's
    // no longer a placement candidate, but still well within the combined grace window.
    clock.advance(nodeDarkTimeout.plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    List<JobRun> stillWithinGrace = store.listJobRunsFor("nightly-cleanup");
    assertEquals(
        1, stillWithinGrace.size(), "a merely-dark node's run must not be abandoned or retried");
    assertEquals(0, stillWithinGrace.get(0).attempt());
    assertEquals("node-a", stillWithinGrace.get(0).nodeId());
    assertFalse(store.getJobPhase("nightly-cleanup").isPresent());
  }

  @Test
  void a_run_on_a_genuinely_gone_node_is_retried_once_the_grace_period_elapses(TestClock clock) {
    StateStore store = new StateStore(tempDir.resolve("store-genuinely-gone"), clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    // Only node-a exists at placement time, so attempt 0 is guaranteed to land there.
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    Duration nodeDarkTimeout = Duration.ofSeconds(15);
    Duration placementGracePeriod = Duration.ofSeconds(30);
    JobReconciler reconciler =
        new JobReconciler(
            store,
            scheduler,
            mutation -> mutation.applyTo(store),
            nodeDarkTimeout,
            placementGracePeriod,
            clock);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor("nightly-cleanup").get(0);
    assertEquals("node-a", attempt0.nodeId());
    reportRunState(store, attempt0, "ACTIVE");

    clock.advance(nodeDarkTimeout.plus(placementGracePeriod).plusSeconds(1));
    // node-b's own heartbeat is refreshed right before this tick -- it stays eligible throughout,
    // so it's the node the retry lands on once node-a is given up on, proving genuine loss still
    // finds its way back to a healthy node rather than getting stuck forever.
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    reconciler.reconcileOnce();

    List<JobRun> afterGracePeriod = store.listJobRunsFor("nightly-cleanup");
    assertEquals(1, afterGracePeriod.size(), "a lost run is retried, never left duplicated");
    assertEquals(1, afterGracePeriod.get(0).attempt());
    assertEquals("node-b", afterGracePeriod.get(0).nodeId());
    assertFalse(store.getJobPhase("nightly-cleanup").isPresent(), "still within backoffLimit");
  }

  @Test
  void does_not_place_a_second_attempt_while_the_current_one_is_still_running() {
    StateStore store = new StateStore(tempDir.resolve("store-still-running"));
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor("nightly-cleanup").get(0);
    reportRunState(store, attempt0, "ACTIVE");

    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor("nightly-cleanup");
    assertEquals(1, runs.size());
    assertEquals(0, runs.get(0).attempt());
    assertFalse(store.getJobPhase("nightly-cleanup").isPresent());
  }
}
