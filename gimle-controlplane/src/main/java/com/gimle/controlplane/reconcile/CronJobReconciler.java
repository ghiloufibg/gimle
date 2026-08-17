package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleId;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.cron.CronSchedule;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * {@link #materializeFiring} with the scheduled path but deliberately never touches {@code
 * cronJobLastSchedule} -- a manual trigger is a one-off action independent of the schedule, not a
 * scheduled firing standing in for one (matching {@code kubectl create job --from=cronjob/x}'s own
 * behavior, which never advances the CronJob controller's own state either).
 */
public final class CronJobReconciler {

  private static final Logger log = LoggerFactory.getLogger(CronJobReconciler.class);

  private final StoreReader store;
  private final MutationSink mutations;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;

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
  }

  public void reconcileOnce() {
    for (CronJobSpec spec : store.listCronJobSpecs()) {
      try {
        reconcileCronJob(spec);
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
    Optional<Instant> lastScheduleTime = store.getCronJobLastSchedule(spec.name());
    if (lastScheduleTime.isEmpty()) {
      // First tick this CronJob has ever been reconciled -- see this class's own javadoc for why
      // this deliberately does not retroactively fire anything.
      mutations.propose(new StateMutation.PutCronJobLastSchedule(spec.name(), now));
      return;
    }

    CronSchedule schedule = CronSchedule.parse(spec.schedule());
    Optional<Instant> due = schedule.mostRecentDueInstant(lastScheduleTime.get(), now);
    if (due.isEmpty()) {
      return; // nothing new due since the last time this ticked; wait for the next tick
    }
    // Recorded regardless of whether this firing is honored or logged as missed below -- either
    // way, this instant (and everything before it) must never be reconsidered on a later tick.
    mutations.propose(new StateMutation.PutCronJobLastSchedule(spec.name(), due.get()));

    if (spec.startingDeadline().isPresent()
        && Duration.between(due.get(), now).compareTo(spec.startingDeadline().get()) > 0) {
      log.warn(
          "cronjob {} missed its schedule at {} (starting deadline {} exceeded); skipping",
          spec.name(),
          due.get(),
          spec.startingDeadline().get());
      return;
    }

    materializeFiring(spec, due.get());
  }

  /**
   * Fires {@code cronJobName} immediately, regardless of its own schedule -- the {@code gimle
   * cronjob trigger} verb's server-side implementation. Returns the generated {@link JobSpec}'s
   * name, or empty if the CronJob doesn't exist or {@link
   * com.gimle.mimir.manifest.ConcurrencyPolicy#FORBID} blocked it.
   */
  public Optional<String> triggerNow(String cronJobName) {
    Optional<CronJobSpec> spec = store.getCronJobSpec(cronJobName);
    if (spec.isEmpty()) {
      return Optional.empty();
    }
    return materializeFiring(spec.get(), clock.instant());
  }

  /**
   * Applies {@link CronJobSpec#concurrencyPolicy()} against every non-terminal {@link JobSpec} this
   * CronJob has previously generated (identified by the {@code {name}-} naming-convention prefix,
   * not a separate tracked reference -- consistent with every other reconciler in this codebase
   * re-deriving its picture from the full snapshot each tick rather than caching it), then, unless
   * {@code FORBID} blocked it, materializes a fresh {@link JobSpec} named {@code
   * {cronJobName}-{epochSeconds}}.
   */
  private Optional<String> materializeFiring(CronJobSpec spec, Instant firingTime) {
    List<JobSpec> generated =
        store.listJobSpecs().stream().filter(s -> s.name().startsWith(spec.name() + "-")).toList();
    List<JobSpec> nonTerminal =
        generated.stream().filter(s -> store.getJobPhase(s.name()).isEmpty()).toList();
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
            nonTerminal.forEach(s -> mutations.propose(new StateMutation.RemoveJobSpec(s.name())));
        case ALLOW -> {
          // Nothing extra -- the new firing runs alongside whatever is still non-terminal.
        }
      }
    }

    Optional<String> artifactSha256 =
        readArtifactSha256(
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
            artifactSha256,
            spec.jobTemplate().vessel());
    mutations.propose(new StateMutation.PutJobSpec(job));
    return Optional.of(jobName);
  }

  /**
   * The digest is never trusted from the manifest -- recomputed at firing time the same way {@code
   * ApiServer}'s own {@code handlePutJob} recomputes it at admission. An unreadable artifact does
   * not block the firing (unlike a directly-submitted Job, nothing has accepted a client's request
   * that must be answered synchronously here) -- {@code JobReconciler}'s own {@code placeAttempt}
   * will simply find the artifact unreadable and retry next tick, the same outcome a missing
   * hash-check would produce anyway. {@code vessel} is threaded through so a vessel-hosted
   * jobTemplate reads its jar the same vessel-aware way every other reconciler does.
   */
  private Optional<String> readArtifactSha256(
      String artifactPath, ModuleId moduleId, Optional<VesselSpec> vessel) {
    try {
      return Optional.of(artifactResolver.resolve(artifactPath, moduleId, vessel).sha256());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
