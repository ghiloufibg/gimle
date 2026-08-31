package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.admission.AdmissionChain;
import com.gimle.controlplane.admission.AdmissionDecision;
import com.gimle.controlplane.admission.LimitRangePlugin;
import com.gimle.controlplane.admission.TenantQuotaPlugin;
import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Verb;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.cron.CronSchedule;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thin policy generator over {@link JobSpec}/{@code JobReconciler} -- never a second execution
 * engine. Every tick, for each non-terminal {@link CronJobSpec}: computes the most recent cron-due
 * instant since that CronJob's own {@code cronJobLastSchedule}, and if one is found, either
 * materializes it as a normal {@link JobSpec} (via an ordinary {@code PutJobSpec} mutation, subject
 * to {@link com.gimle.mimir.manifest.ConcurrencyPolicy}) or logs it as missed if {@link
 * CronJobSpec#startingDeadline()} has already elapsed -- matching Kubernetes CronJob's own
 * well-understood missed-schedule handling rather than inventing a new one. This reconciler never
 * touches {@link com.gimle.mimir.store.JobRun} or {@link com.gimle.controlplane.schedule.Scheduler}
 * directly -- placement, retries, and completion are entirely {@code JobReconciler}'s unchanged
 * responsibility once a {@link JobSpec} exists, the same layering {@code
 * AutoscaleReconciler}/{@code DeploymentReconciler} already have (the autoscaler only ever writes
 * {@code effectiveReplicas}, never touches {@code InstanceAssignment}).
 *
 * <p><b>{@code cronJobLastSchedule} starts empty, not at epoch</b>: a freshly-submitted CronJob's
 * first tick finds no recorded last-schedule time, and rather than treating "never recorded" as
 * "due since the beginning of time" (which would fire every missed minute since 1970 in one burst),
 * this reconciler simply records {@code now} as the baseline and waits for the next tick -- a
 * CronJob's schedule effectively starts counting from whenever the control plane first reconciles
 * it, not retroactively.
 *
 * <p>{@link #triggerNow} is the manual-fire path ({@code gimle cronjob trigger <name>}): it shares
 * {@link #planFiring} with the scheduled path but deliberately never touches {@code
 * cronJobLastSchedule} -- a manual trigger is a one-off action independent of the schedule, not a
 * scheduled firing standing in for one (matching {@code kubectl create job --from=cronjob/x}'s own
 * behavior, which never advances the CronJob controller's own state either).
 *
 * <p>Every generated {@link JobSpec} runs through the identical {@link
 * com.gimle.controlplane.admission.LimitRangePlugin}/{@link
 * com.gimle.controlplane.admission.TenantQuotaPlugin} chain {@code ApiServer.handlePutJob} applies
 * to a directly-submitted Job -- see {@link
 * com.gimle.controlplane.admission.WorkloadResourceProfile}'s own javadoc for why a {@link
 * CronJobSpec} itself has nothing to charge, but each firing it produces is a real, chargeable Job.
 * A rejected firing is treated exactly like a missed one (logged, {@code cronJobLastSchedule} still
 * advances so it isn't retried every tick) rather than failing the whole reconcile pass -- the same
 * tolerant posture {@link #readArtifactSha256} already had for an unreadable artifact.
 *
 * <p>Every tick also prunes each CronJob's own terminal generated Jobs down to {@link
 * CronJobSpec#successfulJobsHistoryLimit()}/{@link CronJobSpec#failedJobsHistoryLimit()} (see
 * {@link #pruneJobHistory}) -- otherwise a CronJob firing on any regular schedule leaves one {@link
 * JobSpec} in the store per firing, forever.
 */
public final class CronJobReconciler {

  private static final Logger log = LoggerFactory.getLogger(CronJobReconciler.class);

  private final StoreReader store;
  private final MutationSink mutations;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;
  private final AdmissionChain<WorkloadSpec> workloadAdmissionChain;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public CronJobReconciler(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  public CronJobReconciler(StoreReader store, MutationSink mutations) {
    this(store, mutations, Clock.systemUTC());
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public CronJobReconciler(StoreReader store, MutationSink mutations, Clock clock) {
    this(store, mutations, clock, ArtifactResolver.localOnly());
  }

  public CronJobReconciler(
      StoreReader store, MutationSink mutations, Clock clock, ArtifactResolver artifactResolver) {
    this.store = store;
    this.mutations = mutations;
    this.clock = clock;
    this.artifactResolver = artifactResolver;
    this.workloadAdmissionChain =
        new AdmissionChain<>(
            List.of(new LimitRangePlugin(), new TenantQuotaPlugin(artifactResolver)));
  }

  public void reconcileOnce() {
    for (CronJobSpec spec : store.listCronJobSpecs()) {
      try {
        reconcileCronJob(spec);
        pruneJobHistory(spec);
      } catch (RuntimeException e) {
        // One cronjob's failure (e.g. a GimleRaftException from mutations.propose during a store
        // leader-election gap) must never abort the rest of this tick's cronjobs -- the next tick
        // retries this one from the same full snapshot.
        log.warn("reconcile of cronjob {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileCronJob(CronJobSpec spec) {
    Instant now = clock.instant();
    Optional<Instant> lastScheduleTime = store.getCronJobLastSchedule(spec.tenantId(), spec.name());
    if (lastScheduleTime.isEmpty()) {
      // First tick this CronJob has ever been reconciled -- see this class's own javadoc for why
      // this deliberately does not retroactively fire anything.
      mutations.propose(
          new StateMutation.PutCronJobLastSchedule(spec.tenantId(), spec.name(), now));
      return;
    }

    CronSchedule schedule = CronSchedule.parse(spec.schedule());
    Optional<Instant> due = schedule.mostRecentDueInstant(lastScheduleTime.get(), now);
    if (due.isEmpty()) {
      return; // nothing new due since the last time this ticked; wait for the next tick
    }
    // The last-schedule advance rides the same batch as the firing it accounts for: recorded
    // regardless of whether the firing is honored or logged as missed below (this instant, and
    // everything before it, must never be reconsidered on a later tick), and committed atomically
    // with the generated JobSpec when there is one -- an advance can no longer land while its
    // firing is lost to a crash in between.
    List<StateMutation> firing = new ArrayList<>();
    firing.add(new StateMutation.PutCronJobLastSchedule(spec.tenantId(), spec.name(), due.get()));

    if (spec.startingDeadline().isPresent()
        && Duration.between(due.get(), now).compareTo(spec.startingDeadline().get()) > 0) {
      log.warn(
          "cronjob {} missed its schedule at {} (starting deadline {} exceeded); skipping",
          spec.name(),
          due.get(),
          spec.startingDeadline().get());
      mutations.proposeAll(firing);
      return;
    }

    planFiring(spec, due.get()).ifPresent(plan -> firing.addAll(plan.mutations()));
    mutations.proposeAll(firing);
  }

  /**
   * Prunes terminal ({@code SUCCEEDED}/{@code FAILED}) generated Jobs down to {@link
   * CronJobSpec#successfulJobsHistoryLimit()}/{@link CronJobSpec#failedJobsHistoryLimit()} per
   * outcome, oldest-first -- entirely independent of {@link
   * com.gimle.mimir.manifest.ConcurrencyPolicy}, which only ever governs non-terminal jobs (see
   * {@link #planFiring}). Runs every tick regardless of whether this tick produced a new firing, so
   * it converges from any starting state -- including one with far more accumulated terminal jobs
   * than the current limit, e.g. right after an operator lowers it.
   */
  private void pruneJobHistory(CronJobSpec spec) {
    List<JobSpec> generated =
        store.listJobSpecs().stream()
            .filter(
                s -> s.name().startsWith(spec.name() + "-") && s.tenantId().equals(spec.tenantId()))
            .toList();
    List<StateMutation> removals = new ArrayList<>();
    removals.addAll(
        excessTerminalJobs(generated, JobPhase.SUCCEEDED, spec.successfulJobsHistoryLimit()));
    removals.addAll(excessTerminalJobs(generated, JobPhase.FAILED, spec.failedJobsHistoryLimit()));
    if (!removals.isEmpty()) {
      mutations.proposeAll(removals);
    }
  }

  /** The oldest-first excess of {@code generated}'s own {@code phase} jobs beyond {@code limit}. */
  private List<StateMutation> excessTerminalJobs(
      List<JobSpec> generated, JobPhase phase, int limit) {
    List<JobSpec> terminalOfPhase =
        generated.stream()
            .filter(s -> store.getJobPhase(s.tenantId(), s.name()).equals(Optional.of(phase)))
            .sorted(Comparator.comparingLong(CronJobReconciler::firingEpochSecond))
            .toList();
    if (terminalOfPhase.size() <= limit) {
      return List.of();
    }
    return terminalOfPhase.stream()
        .limit(terminalOfPhase.size() - limit)
        .<StateMutation>map(s -> new StateMutation.RemoveJobSpec(s.tenantId(), s.name()))
        .toList();
  }

  /**
   * Extracts the {@code epochSeconds} suffix a generated Job's own name carries ({@code
   * {cronJobName}-{epochSeconds}}, see {@link #planFiring}) to order terminal jobs oldest-first for
   * history pruning. Falls back to {@code 0} for a suffix that isn't a valid number -- never
   * produced by this reconciler itself, but harmless: such a job just sorts first, so pruning still
   * proceeds rather than one malformed name blocking the whole tick.
   */
  private static long firingEpochSecond(JobSpec job) {
    String name = job.name();
    try {
      return Long.parseLong(name.substring(name.lastIndexOf('-') + 1));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /**
   * Fires {@code cronJobName} immediately, regardless of its own schedule -- the {@code gimle
   * cronjob trigger} verb's server-side implementation. Returns the generated {@link JobSpec}'s
   * name, or empty if the CronJob doesn't exist or {@link
   * com.gimle.mimir.manifest.ConcurrencyPolicy#FORBID} blocked it.
   */
  public Optional<String> triggerNow(Optional<String> tenantHint, String cronJobName) {
    Optional<CronJobSpec> spec = store.getCronJobSpec(tenantHint, cronJobName);
    if (spec.isEmpty()) {
      return Optional.empty();
    }
    return planFiring(spec.get(), clock.instant())
        .map(
            plan -> {
              mutations.proposeAll(plan.mutations());
              return plan.jobName();
            });
  }

  /** What one honored firing commits, as a single batch: any REPLACE removals, then the JobSpec. */
  private record Firing(String jobName, List<StateMutation> mutations) {}

  /**
   * Applies {@link CronJobSpec#concurrencyPolicy()} against every non-terminal {@link JobSpec} this
   * CronJob has previously generated (identified by the {@code {name}-} naming-convention prefix,
   * not a separate tracked reference -- consistent with every other reconciler in this codebase
   * re-deriving its picture from the full snapshot each tick rather than caching it), then, unless
   * {@code FORBID} blocked it, materializes a fresh {@link JobSpec} named {@code
   * {cronJobName}-{epochSeconds}}.
   */
  private Optional<Firing> planFiring(CronJobSpec spec, Instant firingTime) {
    List<StateMutation> planned = new ArrayList<>();
    List<JobSpec> generated =
        store.listJobSpecs().stream()
            .filter(
                s -> s.name().startsWith(spec.name() + "-") && s.tenantId().equals(spec.tenantId()))
            .toList();
    List<JobSpec> nonTerminal =
        generated.stream()
            .filter(s -> store.getJobPhase(s.tenantId(), s.name()).isEmpty())
            .toList();
    if (!nonTerminal.isEmpty()) {
      switch (spec.concurrencyPolicy()) {
        case FORBID -> {
          log.info(
              "cronjob {} firing skipped: {} still non-terminal and concurrencyPolicy is FORBID",
              spec.name(),
              nonTerminal.get(0).name());
          return Optional.empty();
        }
        case REPLACE ->
            nonTerminal.forEach(
                s -> planned.add(new StateMutation.RemoveJobSpec(s.tenantId(), s.name())));
        case ALLOW -> {
          // Nothing extra -- the new firing runs alongside whatever is still non-terminal.
        }
      }
    }

    Optional<ModuleArtifact> artifact =
        resolveArtifactIfPossible(
            spec.jobTemplate().artifactPath(),
            spec.jobTemplate().moduleId(),
            spec.jobTemplate().vessel());
    String jobName = spec.name() + "-" + firingTime.getEpochSecond();
    JobSpec job =
        new JobSpec(
            jobName,
            spec.jobTemplate().moduleId(),
            spec.jobTemplate().artifactPath(),
            spec.jobTemplate().placement(),
            spec.jobTemplate().activeDeadline(),
            spec.jobTemplate().backoffLimit(),
            spec.tenantId(),
            artifact.map(ModuleArtifact::sha256),
            spec.jobTemplate().vessel());
    AdmissionDecision<WorkloadSpec> decision =
        workloadAdmissionChain.admit(ResourceKind.JOB, Verb.WRITE, job, store, artifact);
    return switch (decision) {
      case AdmissionDecision.Reject<WorkloadSpec> reject -> {
        log.warn("cronjob {} firing at {} skipped: {}", spec.name(), firingTime, reject.reason());
        yield Optional.empty();
      }
      case AdmissionDecision.Allow<WorkloadSpec> allow -> {
        planned.add(new StateMutation.PutJobSpec((JobSpec) allow.spec()));
        yield Optional.of(new Firing(jobName, List.copyOf(planned)));
      }
    };
  }

  /**
   * The digest is never trusted from the manifest -- recomputed at firing time the same way {@code
   * ApiServer}'s own {@code handlePutJob} recomputes it at admission. An unreadable artifact does
   * not block the firing directly (unlike a directly-submitted Job, nothing has accepted a client's
   * request that must be answered synchronously here) -- but for an <em>enforceable</em> tenant,
   * {@link TenantQuotaPlugin}/{@link LimitRangePlugin} both reject an unreadable artifact outright
   * (they have no way to verify quota/limit-range against it), so the firing is skipped the same
   * way a genuine over-quota firing is. For an untenanted or unenforceable CronJob, both plugins
   * allow it straight through regardless, and {@code JobReconciler}'s own {@code placeAttempt}
   * simply finds the artifact unreadable and retries next tick. {@code vessel} is threaded through
   * so a vessel-hosted jobTemplate reads its jar the same vessel-aware way every other reconciler
   * does.
   */
  private Optional<ModuleArtifact> resolveArtifactIfPossible(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    try {
      return Optional.of(artifactResolver.resolve(artifactPath, moduleId, vessel));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
