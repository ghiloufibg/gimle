package com.gimle.controlplane.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

  /** Kubernetes' own CronJob defaults: 3 succeeded / 1 failed kept, see {@link CronJobSpec}. */
  private static final int DEFAULT_SUCCESSFUL_LIMIT = 3;

  private static final int DEFAULT_FAILED_LIMIT = 1;

  private CronJobSpec cronJob(String name, Path jar, String schedule, ConcurrencyPolicy policy) {
    return cronJob(name, jar, schedule, policy, DEFAULT_SUCCESSFUL_LIMIT, DEFAULT_FAILED_LIMIT);
  }

  private CronJobSpec cronJob(
      String name,
      Path jar,
      String schedule,
      ConcurrencyPolicy policy,
      int successfulJobsHistoryLimit,
      int failedJobsHistoryLimit) {
    JobTemplate template =
        new JobTemplate(
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6);
    return new CronJobSpec(
        name,
        schedule,
        template,
        Optional.empty(),
        policy,
        Optional.empty(),
        successfulJobsHistoryLimit,
        failedJobsHistoryLimit);
  }

  private static List<JobSpec> generatedJobsFor(StateStore store, String cronJobName) {
    return store.listJobSpecs().stream()
        .filter(s -> s.name().startsWith(cronJobName + "-"))
        .toList();
  }

  /**
   * The job {@code CronJobReconciler#planFiring} most recently generated, identified by the {@code
   * epochSeconds} suffix its own name carries -- {@code generatedJobsFor} reads {@code
   * store.listJobSpecs()}, backed by a {@code ConcurrentHashMap}, so its iteration order is not
   * insertion order and the last element of that list is not reliably the most recently fired job.
   */
  private static JobSpec mostRecentlyFired(List<JobSpec> generated) {
    return generated.stream()
        .max(
            Comparator.comparingLong(
                s -> Long.parseLong(s.name().substring(s.name().lastIndexOf('-') + 1))))
        .orElseThrow();
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
            Optional.empty(),
            DEFAULT_SUCCESSFUL_LIMIT,
            DEFAULT_FAILED_LIMIT);
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

  private JobSpec terminalJob(String cronJobName, CronJobSpec ownerSpec, long epochSecond) {
    return new JobSpec(
        cronJobName + "-" + epochSecond,
        ownerSpec.jobTemplate().moduleId(),
        ownerSpec.jobTemplate().artifactPath(),
        PlacementConstraints.NONE,
        Optional.empty(),
        6,
        ownerSpec.tenantId(),
        Optional.empty());
  }

  /**
   * Simulates a control-plane restart landing on a snapshot with far more accumulated terminal Jobs
   * than the currently-configured limits allow -- e.g. right after an operator lowers {@code
   * successfulJobsHistoryLimit}/{@code failedJobsHistoryLimit} on an already-long-running CronJob.
   * A single tick, with nothing newly due (the schedule never fires), must still prune down to
   * exactly the configured limit per outcome, oldest-first, while leaving alone both the
   * still-non-terminal job and a different CronJob's own terminal job that happens to be under its
   * own limit already.
   */
  @Test
  void
      an_arbitrary_starting_snapshot_with_far_more_terminal_jobs_than_configured_converges_by_pruning_the_oldest_first() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    // Never fires -- isolates pruning from this tick's own firing logic, the same trick
    // trigger_now_fires_immediately_and_does_not_touch_last_schedule_time already relies on.
    CronJobSpec spec = cronJob("nightly-cleanup", jar, "0 0 30 2 *", ConcurrencyPolicy.ALLOW, 2, 1);
    store.putCronJobSpec(spec);

    for (long epoch : List.of(100L, 200L, 300L, 400L, 500L)) {
      JobSpec job = terminalJob("nightly-cleanup", spec, epoch);
      store.putJobSpec(job);
      store.putJobPhase(Optional.empty(), job.name(), JobPhase.SUCCEEDED);
    }
    for (long epoch : List.of(150L, 250L, 350L)) {
      JobSpec job = terminalJob("nightly-cleanup", spec, epoch);
      store.putJobSpec(job);
      store.putJobPhase(Optional.empty(), job.name(), JobPhase.FAILED);
    }
    JobSpec nonTerminal = terminalJob("nightly-cleanup", spec, 600L);
    store.putJobSpec(nonTerminal); // no phase recorded -- still running

    CronJobSpec otherSpec =
        cronJob("other-cleanup", jar, "0 0 30 2 *", ConcurrencyPolicy.ALLOW, 1, 1);
    store.putCronJobSpec(otherSpec);
    JobSpec otherJob = terminalJob("other-cleanup", otherSpec, 100L);
    store.putJobSpec(otherJob);
    store.putJobPhase(Optional.empty(), otherJob.name(), JobPhase.SUCCEEDED);

    new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock).reconcileOnce();

    List<JobSpec> remainingForNightly = generatedJobsFor(store, "nightly-cleanup");
    Set<String> remainingSucceeded =
        remainingForNightly.stream()
            .filter(
                s ->
                    store
                        .getJobPhase(Optional.empty(), s.name())
                        .equals(Optional.of(JobPhase.SUCCEEDED)))
            .map(JobSpec::name)
            .collect(Collectors.toSet());
    Set<String> remainingFailed =
        remainingForNightly.stream()
            .filter(
                s ->
                    store
                        .getJobPhase(Optional.empty(), s.name())
                        .equals(Optional.of(JobPhase.FAILED)))
            .map(JobSpec::name)
            .collect(Collectors.toSet());

    assertEquals(
        Set.of("nightly-cleanup-400", "nightly-cleanup-500"),
        remainingSucceeded,
        "kept the 2 most recent SUCCEEDED jobs, pruned the 3 oldest");
    assertEquals(
        Set.of("nightly-cleanup-350"),
        remainingFailed,
        "kept the 1 most recent FAILED job, pruned the 2 oldest");
    assertTrue(
        store.getJobSpec(Optional.empty(), "nightly-cleanup-600").isPresent(),
        "the still-non-terminal job must never be pruned");
    assertTrue(
        store.getJobSpec(Optional.empty(), "other-cleanup-100").isPresent(),
        "a different cronjob's own terminal job, already within its own limit, must be untouched");
  }

  /**
   * The end-to-end version of the pruning test above: real firings, real {@code JobPhase}
   * transitions, real ticks -- rather than a hand-seeded snapshot -- converging to Kubernetes
   * CronJob's own default limits (3 succeeded / 1 failed, see {@link CronJobSpec}).
   */
  @Test
  void repeated_real_firings_marked_terminal_converge_to_the_default_history_limits() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    Path jar = buildFixtureJar();
    store.putCronJobSpec(cronJob("nightly-cleanup", jar, "* * * * *", ConcurrencyPolicy.ALLOW));
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce(); // baseline

    // 6 firings: 4 SUCCEEDED, 2 FAILED -- both exceed the default limits (3 succeeded / 1 failed).
    List<JobPhase> outcomes =
        List.of(
            JobPhase.SUCCEEDED,
            JobPhase.FAILED,
            JobPhase.SUCCEEDED,
            JobPhase.SUCCEEDED,
            JobPhase.FAILED,
            JobPhase.SUCCEEDED);
    for (JobPhase outcome : outcomes) {
      clock.advance(Duration.ofMinutes(1));
      reconciler.reconcileOnce();
      JobSpec justFired = mostRecentlyFired(generatedJobsFor(store, "nightly-cleanup"));
      store.putJobPhase(Optional.empty(), justFired.name(), outcome);
      reconciler.reconcileOnce(); // prunes on the very next tick, no firing due this minute
    }

    List<JobSpec> remaining = generatedJobsFor(store, "nightly-cleanup");
    long succeededCount =
        remaining.stream()
            .filter(
                s ->
                    store
                        .getJobPhase(Optional.empty(), s.name())
                        .equals(Optional.of(JobPhase.SUCCEEDED)))
            .count();
    long failedCount =
        remaining.stream()
            .filter(
                s ->
                    store
                        .getJobPhase(Optional.empty(), s.name())
                        .equals(Optional.of(JobPhase.FAILED)))
            .count();
    assertEquals(3, succeededCount, "converged to the default successfulJobsHistoryLimit");
    assertEquals(1, failedCount, "converged to the default failedJobsHistoryLimit");
  }

  /**
   * The generated {@link JobSpec} is a real, chargeable workload even though the {@link
   * CronJobSpec} itself is not (see {@code WorkloadResourceProfile}'s own javadoc) -- a tenant
   * cannot bypass quota enforcement simply by wrapping an over-sized Job in a CronJob.
   */
  @Test
  void a_firing_that_would_exceed_its_tenants_quota_is_skipped_like_a_missed_firing() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    store.putTenant(new Tenant("tight", new ResourceQuota(1, 1, 1)));
    Path jar = buildFixtureJar();
    CronJobSpec spec = tenantedCronJob("nightly-cleanup", jar, "tight");
    store.putCronJobSpec(spec);
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce(); // establishes the baseline, fires nothing yet

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce();

    assertTrue(
        generatedJobsFor(store, "nightly-cleanup").isEmpty(),
        "an over-quota firing must never materialize a JobSpec");
    assertEquals(
        Instant.parse("2026-01-01T00:01:00Z"),
        store.getCronJobLastSchedule(Optional.of("tight"), "nightly-cleanup").orElseThrow(),
        "a rejected firing is still recorded as scheduled, the same as a missed one");
  }

  private CronJobSpec tenantedCronJob(String name, Path jar, String tenantId) {
    JobTemplate template =
        new JobTemplate(
            new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
            jar.toAbsolutePath().toString(),
            PlacementConstraints.NONE,
            Optional.empty(),
            6);
    return new CronJobSpec(
        name,
        "* * * * *",
        template,
        Optional.empty(),
        ConcurrencyPolicy.ALLOW,
        Optional.of(tenantId),
        DEFAULT_SUCCESSFUL_LIMIT,
        DEFAULT_FAILED_LIMIT);
  }

  /**
   * Regression test for the tenant-scoping gap in {@code planFiring}'s own generated-Job lookup
   * (unlike the identically-shaped lookup in {@code pruneJobHistory}, which already scopes on
   * {@code tenantId}): two tenants each own a CronJob with the same name, so their generated Jobs
   * share the same {@code {name}-} prefix. Tenant B's REPLACE firing must never treat tenant A's
   * still-running Job as its own to replace.
   */
  @Test
  void a_replace_firing_never_removes_a_different_tenants_colliding_prefix_job() {
    TestClock clock = new TestClock();
    StateStore store = new StateStore(clock);
    store.putTenant(new Tenant("tenant-a", new ResourceQuota(Long.MAX_VALUE, Long.MAX_VALUE, 100)));
    store.putTenant(new Tenant("tenant-b", new ResourceQuota(Long.MAX_VALUE, Long.MAX_VALUE, 100)));
    Path jar = buildFixtureJar();
    CronJobSpec tenantASpec =
        new CronJobSpec(
            "shared-name",
            "* * * * *",
            jobTemplateFor(jar),
            Optional.empty(),
            ConcurrencyPolicy.ALLOW,
            Optional.of("tenant-a"),
            DEFAULT_SUCCESSFUL_LIMIT,
            DEFAULT_FAILED_LIMIT);
    // Never fires on its own schedule -- isolated from tenant A's own firing tick below, fired
    // instead via triggerNow so it lands strictly after tenant A already has a non-terminal Job.
    CronJobSpec tenantBSpec =
        new CronJobSpec(
            "shared-name",
            "0 0 30 2 *",
            jobTemplateFor(jar),
            Optional.empty(),
            ConcurrencyPolicy.REPLACE,
            Optional.of("tenant-b"),
            DEFAULT_SUCCESSFUL_LIMIT,
            DEFAULT_FAILED_LIMIT);
    store.putCronJobSpec(tenantASpec);
    store.putCronJobSpec(tenantBSpec);
    CronJobReconciler reconciler =
        new CronJobReconciler(store, mutation -> mutation.applyTo(store), clock);
    reconciler.reconcileOnce(); // baseline for both

    clock.advance(Duration.ofMinutes(1));
    reconciler.reconcileOnce(); // tenant A's cronjob fires; tenant B's never due
    List<JobSpec> tenantAJobs =
        store.listJobSpecs().stream()
            .filter(s -> s.tenantId().equals(Optional.of("tenant-a")))
            .toList();
    assertEquals(1, tenantAJobs.size(), "tenant A's own firing must have materialized");
    String tenantAJobName = tenantAJobs.get(0).name();

    Optional<String> tenantBJobName = reconciler.triggerNow(Optional.of("tenant-b"), "shared-name");

    assertTrue(tenantBJobName.isPresent());
    assertTrue(
        store.getJobSpec(Optional.of("tenant-a"), tenantAJobName).isPresent(),
        "tenant B's REPLACE firing must never remove tenant A's colliding-prefix Job");
  }

  private JobTemplate jobTemplateFor(Path jar) {
    return new JobTemplate(
        new ModuleId(jar.getFileName().toString().replace(".jar", ""), Version.parse("1.0.0")),
        jar.toAbsolutePath().toString(),
        PlacementConstraints.NONE,
        Optional.empty(),
        6);
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
