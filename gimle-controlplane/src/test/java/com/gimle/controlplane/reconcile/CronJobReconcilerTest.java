package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.time.TestClock;
import com.gimle.mimir.manifest.ConcurrencyPolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.JobTemplate;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.StateStore;
import com.gimle.module.testsupport.TestModuleBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mirrors {@link JobReconcilerTest}'s own shape closely -- see that class for why. {@link
 * TestClock#DEFAULT_START} (2026-01-01T00:00:00Z, a Thursday) is the fixed baseline every test
 * advances from.
 */
class CronJobReconcilerTest {

  @TempDir(cleanup = CleanupMode.NEVER)
  Path tempDir;

  private static int counter = 0;

  private Path buildFixtureJar() {
    String uniqueName = "com.gimle.fixture.cronjobreconciler" + (counter++);
    return TestModuleBuilder.module("module " + uniqueName + " {\n}\n")
        .withDescriptor(TestModuleBuilder.minimalDescriptor(uniqueName, "1.0.0"))
        .build(tempDir, uniqueName + ".jar");
  }

  private CronJobSpec cronJob(String name, Path jar, String schedule, ConcurrencyPolicy policy) {
    JobTemplate template =
        new JobTemplate(
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6);
    return new CronJobSpec(name, schedule, template, Optional.empty(), policy, Optional.empty());
  }

  private static List<JobSpec> generatedJobsFor(StateStore store, String cronJobName) {
    return store.listJobSpecs().stream()
        .filter(s -> s.name().startsWith(cronJobName + "-"))
        .toList();
  }

  @Test
  void first_tick_records_a_baseline_and_materializes_nothing() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "0 0 * * *", ConcurrencyPolicy.ALLOW));

    new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock).reconcileOnce();

    assertTrue(generatedJobsFor(store, "nightly-cleanup").isEmpty());
    assertEquals(
        Instant.parse("2026-01-01T00:00:00Z"),
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow());
  }

  @Test
  void a_due_firing_materializes_a_job_named_with_the_epoch_second_suffix() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.ALLOW));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce(); // establishes the baseline, fires nothing yet

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    List<JobSpec> generated = generatedJobsFor(store, "nightly-cleanup");
    assertEquals(1, generated.size());
    assertEquals("nightly-cleanup-" + clock.instant().getEpochSecond(), generated.get(0).name());
    assertEquals(
        Instant.parse("2026-01-01T00:01:00Z"),
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow());
  }

  @Test
  void ticking_again_with_nothing_newly_due_materializes_nothing_more() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "0 0 * * *", ConcurrencyPolicy.ALLOW));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();

    clock.advance(Duration.ofMinutes(1)); // still within the same day, next 00:00 not due yet
    reconciler.reconcileOnce();
    reconciler.reconcileOnce();

    assertTrue(generatedJobsFor(store, "nightly-cleanup").isEmpty());
  }

  @Test
  void
      a_firing_past_its_starting_deadline_is_logged_as_missed_but_last_schedule_time_still_advances() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    JobTemplate template =
        new JobTemplate(
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6);
    // Once daily at 01:00, a real "the control plane was down for a while" scenario -- unlike
    // "* * * * *", which always finds a due instant within the last 59s of any tick and so can
    // never model a genuinely missed firing this cleanly.
    CronJobSpec spec =
        new CronJobSpec(
            "nightly-cleanup",
            "0 1 * * *",
            template,
            Optional.of(Duration.ofMinutes(5)),
            ConcurrencyPolicy.ALLOW,
            Optional.empty());
    store.putCronJobSpec(spec);
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce(); // baseline: 2026-01-01T00:00:00Z

    // 25h10m later: the 01:00 firing due at 2026-01-02T01:00:00Z is now 10 minutes in the past,
    // past the 5-minute starting deadline.
    clock.advance(Duration.ofHours(25)).advance(Duration.ofMinutes(10));
    reconciler.reconcileOnce();

    assertTrue(generatedJobsFor(store, "nightly-cleanup").isEmpty());
    assertEquals(
        Instant.parse("2026-01-02T01:00:00Z"),
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow(),
        "the missed instant is still recorded so it's never reconsidered");
  }

  @Test
  void concurrency_policy_allow_lets_a_new_firing_run_alongside_a_still_running_one() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.ALLOW));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();
    assertEquals(1, generatedJobsFor(store, "nightly-cleanup").size());
    // The first firing is still non-terminal (no JobPhase recorded).

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    assertEquals(2, generatedJobsFor(store, "nightly-cleanup").size());
  }

  @Test
  void concurrency_policy_forbid_skips_a_firing_while_the_previous_one_is_still_running() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.FORBID));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();
    assertEquals(1, generatedJobsFor(store, "nightly-cleanup").size());

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    assertEquals(
        1,
        generatedJobsFor(store, "nightly-cleanup").size(),
        "FORBID must not add a second non-terminal Job while the first is still running");
  }

  @Test
  void concurrency_policy_forbid_allows_the_next_firing_once_the_previous_one_is_terminal() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.FORBID));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();
    JobSpec firstFiring = generatedJobsFor(store, "nightly-cleanup").get(0);
    store.putJobPhase(Optional.empty(), firstFiring.name(), JobPhase.SUCCEEDED);

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    assertEquals(2, generatedJobsFor(store, "nightly-cleanup").size());
  }

  @Test
  void concurrency_policy_replace_removes_the_still_running_job_before_placing_the_new_one() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.REPLACE));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();
    JobSpec firstFiring = generatedJobsFor(store, "nightly-cleanup").get(0);

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    List<JobSpec> generated = generatedJobsFor(store, "nightly-cleanup");
    assertEquals(1, generated.size(), "REPLACE keeps exactly one non-terminal Job at a time");
    assertFalse(
        generated.get(0).name().equals(firstFiring.name()),
        "the new firing replaces the old one, not the other way around");
    assertTrue(store.getJobSpec(Optional.empty(), firstFiring.name()).isEmpty());
  }

  @Test
  void trigger_now_fires_immediately_and_does_not_touch_last_schedule_time() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    // A schedule that would never naturally fire during this test (Feb 30 doesn't exist -- day-
    // of-month 30 in February) -- makes the point that trigger doesn't depend on the schedule at
    // all, not merely "hasn't happened to fire yet".
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "0 0 30 2 *", ConcurrencyPolicy.ALLOW));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    assertTrue(store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").isPresent());
    Instant baseline =
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow();

    Optional<String> jobName = reconciler.triggerNow(Optional.empty(), "nightly-cleanup");

    assertTrue(jobName.isPresent());
    assertEquals(1, generatedJobsFor(store, "nightly-cleanup").size());
    assertEquals(
        baseline,
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow(),
        "a manual trigger must not advance the schedule's own bookkeeping");
  }

  @Test
  void trigger_now_on_an_unknown_cronjob_returns_empty() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);

    Optional<String> jobName =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock)
            .triggerNow(Optional.empty(), "no-such-cronjob");

    assertTrue(jobName.isEmpty());
  }

  @Test
  void an_arbitrary_starting_snapshot_with_two_stray_non_terminal_runs_converges_under_replace() {
    // Simulates a control-plane restart landing mid-way through materializeFiring's own REPLACE
    // branch (see CronJobReconciler#materializeFiring): cronJobLastSchedule was already advanced
    // for a prior firing, but the removal of the still-non-terminal Job(s) that firing was meant
    // to replace never completed, and a second stray firing also survived from before that -- two
    // non-terminal generated Jobs on record for one REPLACE cronjob at once, a state a from-scratch
    // reconcile would never itself produce. A tick with a fresh firing due must still enforce
    // REPLACE's own invariant -- exactly one non-terminal Job survives -- using nothing but this
    // snapshot.
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    CronJobSpec spec = cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.REPLACE);
    store.putCronJobSpec(spec);
    store.putCronJobLastSchedule(Optional.empty(), "nightly-cleanup", clock.instant());
    JobSpec stray1 =
        new JobSpec(
            "nightly-cleanup-1",
            spec.jobTemplate().moduleId(),
            spec.jobTemplate().artifactPath(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6,
            Optional.empty(),
            Optional.empty());
    JobSpec stray2 =
        new JobSpec(
            "nightly-cleanup-2",
            spec.jobTemplate().moduleId(),
            spec.jobTemplate().artifactPath(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6,
            Optional.empty(),
            Optional.empty());
    store.putJobSpec(stray1);
    store.putJobSpec(stray2);

    clock.advance(Duration.ofMinutes(1));
    new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock).reconcileOnce();

    List<JobSpec> generated = generatedJobsFor(store, "nightly-cleanup");
    assertEquals(
        1, generated.size(), "REPLACE must converge to exactly one non-terminal Job, not two");
    assertTrue(
        store.getJobSpec(Optional.empty(), "nightly-cleanup-1").isEmpty(),
        "the first stray firing must be cleaned up");
    assertTrue(
        store.getJobSpec(Optional.empty(), "nightly-cleanup-2").isEmpty(),
        "the second stray firing must be cleaned up");
    assertEquals(
        Instant.parse("2026-01-01T00:01:00Z"),
        store.getCronJobLastSchedule(Optional.empty(), "nightly-cleanup").orElseThrow());
  }

  @Test
  void trigger_now_respects_forbid_against_a_still_running_previous_firing() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "0 0 30 2 *", ConcurrencyPolicy.FORBID));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce();
    assertTrue(reconciler.triggerNow(Optional.empty(), "nightly-cleanup").isPresent());

    Optional<String> second = reconciler.triggerNow(Optional.empty(), "nightly-cleanup");

    assertTrue(second.isEmpty());
    assertEquals(1, generatedJobsFor(store, "nightly-cleanup").size());
  }
}
