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
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.WorkloadHealthState;
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

  /** Mirrors {@code HealthReconcilerTest}'s own {@code reconciler(store, clock)} helper. */
  private static JobReconciler reconciler(StateStore store, Scheduler scheduler, TestClock clock) {
    return new JobReconciler(
        store,
        scheduler,
        mutation -> mutation.applyTo(store),
        JobReconciler.DEFAULT_NODE_DARK_TIMEOUT,
        JobReconciler.DEFAULT_NODE_DARK_TIMEOUT,
        clock);
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
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new JobReconciler(store, scheduler).reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, runs.size());
    assertEquals(0, runs.get(0).attempt());
    assertEquals(Optional.empty(), store.getJobPhase(Optional.empty(), "nightly-cleanup"));
  }

  @Test
  void leaves_the_job_unplaced_without_throwing_when_no_node_has_capacity() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    // no nodes registered at all

    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    reconciler.reconcileOnce(); // idempotent: calling again doesn't error or duplicate

    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
  }

  @Test
  void a_completed_observation_marks_the_job_succeeded_and_removes_the_run() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun placed = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);

    reportRunState(store, placed, "COMPLETED");
    reconciler.reconcileOnce();

    assertEquals(
        Optional.of(JobPhase.SUCCEEDED), store.getJobPhase(Optional.empty(), "nightly-cleanup"));
    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
    JobRunSummary summary =
        store.getJobRunSummary(Optional.empty(), "nightly-cleanup").orElseThrow();
    assertEquals(placed.attempt(), summary.attempt());
    assertEquals(placed.nodeId(), summary.nodeId());
  }

  @Test
  void a_failed_attempt_is_not_retried_before_its_backoff_elapses(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = reconciler(store, scheduler, clock);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler.reconcileOnce(); // first failure observed: starts the backoff, doesn't retry yet

    List<JobRun> stillWaiting = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, stillWaiting.size(), "the failed attempt stays on record while backoff pends");
    assertEquals(0, stillWaiting.get(0).attempt());
    assertFalse(store.getJobPhase(Optional.empty(), "nightly-cleanup").isPresent());

    // One nanosecond short of JobReconciler's own 2-second initial backoff delay.
    clock.advance(Duration.ofSeconds(2).minusNanos(1));
    reconciler.reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, runs.size());
    assertEquals(
        0, runs.get(0).attempt(), "the backoff has not elapsed yet, so nothing should retry");
    assertFalse(store.getJobPhase(Optional.empty(), "nightly-cleanup").isPresent());
  }

  @Test
  void a_failed_attempt_is_retried_once_its_backoff_elapses(TestClock clock) {
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = reconciler(store, scheduler, clock);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler.reconcileOnce(); // first failure observed: starts the backoff

    clock.advance(Duration.ofSeconds(2)); // JobReconciler's own initial backoff delay, exactly
    reconciler.reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, runs.size(), "the failed attempt is gone, replaced by exactly one retry");
    assertEquals(1, runs.get(0).attempt());
    assertEquals(
        Optional.empty(),
        store.getJobPhase(Optional.empty(), "nightly-cleanup"),
        "still within backoffLimit -- not yet terminal");
  }

  @Test
  void backoff_bookkeeping_survives_a_reconciler_reconstruction_against_the_same_store(
      TestClock clock) {
    // Simulates a reconciler-leader failover: a fresh JobReconciler instance, backed by the same
    // store, must resume the in-progress backoff rather than re-granting attempt 1's own full
    // initial delay a second time.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    reconciler(store, scheduler, clock).reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler(store, scheduler, clock)
        .reconcileOnce(); // first failure recorded, pending retry not yet elapsed
    assertEquals(0, store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0).attempt());

    // Construct a brand-new reconciler against the same store: the store (gimle-mimir) is its own
    // process and doesn't restart with a failed-over reconciler leader, so only the reconciler's
    // own in-memory history is lost -- everything it must resume from lives in the store.
    JobReconciler resumed = reconciler(store, scheduler, clock);

    clock.advance(Duration.ofSeconds(2));
    resumed.reconcileOnce(); // if the pending backoff wasn't resumed, this would start a new one

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(
        1,
        runs.get(0).attempt(),
        "the resumed reconciler should have completed the backoff it didn't start itself");
  }

  @Test
  void converges_correctly_from_an_arbitrary_mix_of_persisted_backoff_states(TestClock clock) {
    // A brand-new reconciler must handle every one of these correctly on its very first tick,
    // with no history of its own -- exactly what a reconciler-leader failover leaves behind.
    StateStore store = new StateStore(clock);
    Scheduler scheduler = new Scheduler();
    long now = clock.instant().toEpochMilli();

    // 1. Failed, already pending retry whose delay elapsed before this reconciler even existed --
    // must retry on this very first tick, not treated as a fresh failure.
    Path overdueJar = buildFixtureJar();
    store.putJobSpec(job("overdue-job", overdueJar, 3, Optional.empty()));
    registerNode(store, "node-overdue", 500L * 1024 * 1024, 4000);
    JobRun overdueRun =
        new JobRun(
            "overdue-job",
            0,
            "node-overdue",
            new ModuleId(
                overdueJar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            overdueJar.toAbsolutePath().toString(),
            Instant.ofEpochMilli(now));
    store.putJobRun(overdueRun);
    reportRunState(store, overdueRun, "FAILED");
    store.putWorkloadHealthState(
        new WorkloadHealthState(
            "Job",
            "overdue-job",
            "0",
            1,
            now - 60_000,
            now - 30_000,
            true,
            false,
            Optional.empty()));

    // 2. Failed, never tracked before -- starts a fresh backoff, must not retry yet.
    Path freshJar = buildFixtureJar();
    store.putJobSpec(job("fresh-failed-job", freshJar, 3, Optional.empty()));
    registerNode(store, "node-fresh", 500L * 1024 * 1024, 4000);
    JobRun freshRun =
        new JobRun(
            "fresh-failed-job",
            0,
            "node-fresh",
            new ModuleId(
                freshJar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            freshJar.toAbsolutePath().toString(),
            Instant.ofEpochMilli(now));
    store.putJobRun(freshRun);
    reportRunState(store, freshRun, "FAILED");

    // One tick, no clock movement at all: the point of case 2 is that its freshly-started backoff
    // has not elapsed, which is now exactly true rather than true-because-the-tick-was-fast.
    reconciler(store, scheduler, clock).reconcileOnce();

    assertEquals(
        1,
        store.listJobRunsFor(Optional.empty(), "overdue-job").get(0).attempt(),
        "an overdue pending retry must fire on the very first tick of a resumed reconciler");
    assertEquals(
        0,
        store.listJobRunsFor(Optional.empty(), "fresh-failed-job").get(0).attempt(),
        "a freshly observed failure starts its own backoff rather than retrying immediately");
  }

  @Test
  void exhausting_the_backoff_limit_marks_the_job_permanently_failed() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 1, Optional.empty())); // backoffLimit: 1
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);

    reportRunState(store, attempt0, "FAILED");
    reconciler.reconcileOnce();

    assertEquals(
        Optional.of(JobPhase.FAILED), store.getJobPhase(Optional.empty(), "nightly-cleanup"));
    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
    // The last attempt's own detail (node, attempt count) survives the JobRun removal above --
    // otherwise get jobs -o json's currentRun field would just disappear the moment a job fails,
    // with nothing left to say which node ran it or how many attempts occurred.
    JobRunSummary summary =
        store.getJobRunSummary(Optional.empty(), "nightly-cleanup").orElseThrow();
    assertEquals(attempt0.attempt(), summary.attempt());
    assertEquals(attempt0.nodeId(), summary.nodeId());
    assertTrue(summary.reason().contains("backoffLimit"), summary.reason());
  }

  @Test
  void exceeding_the_active_deadline_marks_the_job_permanently_failed_even_mid_attempt(
      TestClock clock) {
    StateStore store = new StateStore(clock);
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
    assertEquals(1, store.listJobRunsFor(Optional.empty(), "nightly-cleanup").size());

    clock.advance(Duration.ofMinutes(6)); // past the 5-minute deadline, run still mid-flight
    reconciler.reconcileOnce();

    assertEquals(
        Optional.of(JobPhase.FAILED), store.getJobPhase(Optional.empty(), "nightly-cleanup"));
    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
  }

  @Test
  void a_terminal_job_is_left_alone_on_later_ticks() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    store.putJobPhase(Optional.empty(), "nightly-cleanup", JobPhase.SUCCEEDED);
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);

    new JobReconciler(store, scheduler).reconcileOnce();

    assertTrue(
        store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty(),
        "a job already terminal must never get a new attempt placed");
  }

  @Test
  void deleting_a_job_removes_its_orphaned_run_on_the_next_tick() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    new JobReconciler(store, scheduler).reconcileOnce();
    assertEquals(1, store.listJobRunsFor(Optional.empty(), "nightly-cleanup").size());

    store.removeJobSpec(Optional.empty(), "nightly-cleanup");
    new JobReconciler(store, scheduler).reconcileOnce();

    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
    assertTrue(store.listJobRuns().isEmpty());
  }

  @Test
  void an_arbitrary_starting_snapshot_with_two_coexisting_runs_converges_to_the_highest_attempt() {
    // Simulates a crash between JobReconciler placing a retry and removing its predecessor (see
    // JobReconciler#reconcileCurrentRun's own ordering note): both attempt 0 and attempt 1 are on
    // record for the same job at once. A from-scratch reconcile must clean this down to exactly
    // one run -- the highest attempt -- without needing any history beyond this snapshot.
    StateStore store = new StateStore();
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

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, runs.size(), "only the highest attempt should survive convergence");
    assertEquals(1, runs.get(0).attempt());
  }

  @Test
  void refuses_to_place_when_the_jar_on_disk_no_longer_matches_the_recorded_hash() {
    StateStore store = new StateStore();
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

    assertTrue(store.listJobRunsFor(Optional.empty(), "nightly-cleanup").isEmpty());
  }

  @Test
  void a_run_on_a_dark_but_not_yet_timed_out_node_is_not_relocated(TestClock clock) {
    StateStore store = new StateStore(clock);
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
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);
    reportRunState(store, attempt0, "ACTIVE");

    // node-a stops heartbeating (a partition, not a real failure): past nodeDarkTimeout, so it's
    // no longer a placement candidate, but still well within the combined grace window.
    clock.advance(nodeDarkTimeout.plus(Duration.ofSeconds(1)));
    reconciler.reconcileOnce();

    List<JobRun> stillWithinGrace = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(
        1, stillWithinGrace.size(), "a merely-dark node's run must not be abandoned or retried");
    assertEquals(0, stillWithinGrace.get(0).attempt());
    assertEquals("node-a", stillWithinGrace.get(0).nodeId());
    assertFalse(store.getJobPhase(Optional.empty(), "nightly-cleanup").isPresent());
  }

  @Test
  void a_run_on_a_genuinely_gone_node_is_retried_once_the_grace_period_and_backoff_elapse(
      TestClock clock) {
    StateStore store = new StateStore(clock);
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
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);
    assertEquals("node-a", attempt0.nodeId());
    reportRunState(store, attempt0, "ACTIVE");

    clock.advance(nodeDarkTimeout.plus(placementGracePeriod).plusSeconds(1));
    // node-b's own heartbeat is refreshed right before this tick -- it stays eligible throughout,
    // so it's the node the retry lands on once node-a is given up on, proving genuine loss still
    // finds its way back to a healthy node rather than getting stuck forever.
    registerNode(store, "node-b", 500L * 1024 * 1024, 4000);
    reconciler.reconcileOnce(); // node genuinely gone: starts the retry backoff, doesn't act yet

    List<JobRun> stillWaiting = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, stillWaiting.size());
    assertEquals(0, stillWaiting.get(0).attempt(), "still waiting out the retry backoff");

    clock.advance(Duration.ofSeconds(2)); // JobReconciler's own initial backoff delay
    reconciler.reconcileOnce();

    List<JobRun> afterGracePeriod = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, afterGracePeriod.size(), "a lost run is retried, never left duplicated");
    assertEquals(1, afterGracePeriod.get(0).attempt());
    assertEquals("node-b", afterGracePeriod.get(0).nodeId());
    assertFalse(
        store.getJobPhase(Optional.empty(), "nightly-cleanup").isPresent(),
        "still within backoffLimit");
  }

  @Test
  void does_not_place_a_second_attempt_while_the_current_one_is_still_running() {
    StateStore store = new StateStore();
    Scheduler scheduler = new Scheduler();
    Path jar = buildFixtureJar();
    store.putJobSpec(job("nightly-cleanup", jar, 3, Optional.empty()));
    registerNode(store, "node-a", 500L * 1024 * 1024, 4000);
    JobReconciler reconciler = new JobReconciler(store, scheduler);
    reconciler.reconcileOnce();
    JobRun attempt0 = store.listJobRunsFor(Optional.empty(), "nightly-cleanup").get(0);
    reportRunState(store, attempt0, "ACTIVE");

    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    List<JobRun> runs = store.listJobRunsFor(Optional.empty(), "nightly-cleanup");
    assertEquals(1, runs.size());
    assertEquals(0, runs.get(0).attempt());
    assertFalse(store.getJobPhase(Optional.empty(), "nightly-cleanup").isPresent());
  }
}
