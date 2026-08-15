package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures at most one non-terminal {@link JobRun} exists for every non-terminal {@link JobSpec} --
 * run-to-completion's counterpart to {@link DeploymentReconciler}, following the exact same
 * level-triggered convergence shape: every tick re-derives the full desired-vs-observed picture
 * from the current snapshot rather than reacting to what changed since last tick. Placement itself
 * is genuinely unchanged from {@link DeploymentReconciler}'s own: a Job attempt is, from {@link
 * Scheduler}'s point of view, indistinguishable from a deployment replica -- same resource request,
 * same tier, same placement constraints -- so this reconciler reuses {@link Scheduler#place}
 * exactly, with its own {@link #buildCandidates} building the same {@link NodeCandidate} shape from
 * {@link JobRun}s instead of {@link InstanceAssignment}s.
 *
 * <p>A completed/failed attempt is detected the same way {@link DeploymentReconciler#isReady}
 * detects a ready replica: by reading the placed node's own heartbeat for an {@link
 * InstanceObservation} matching this run's {@code (jobName, attempt)} identity and {@code
 * lifecycleState}. This works with zero agent- or worker-side changes because {@code
 * ApiServer.handleAssignments} maps a {@link JobRun} onto the exact same {@code AssignedInstance}
 * wire shape a deployment replica uses ({@code jobName}/{@code attempt} playing {@code
 * deploymentName}/{@code instanceIndex}'s own role) -- the agent and worker never need to know or
 * care which kind actually placed an assignment; only this reconciler and {@code WorkerRuntime}'s
 * {@code jobHooksClass()} check are Job-kind-aware at all.
 *
 * <p><b>Placement safety</b>: unlike {@link DeploymentReconciler} (whose dark-node cleanup lives in
 * the separate {@code ReplicaCountReconciler}, gated on its own {@code placementGracePeriod}) or
 * {@link StatefulSetReconciler} (which never relocates a sticky index off a node at all), this
 * reconciler's own {@link #reconcileCurrentRun} treats a run's node going dark as a distinct case
 * from an ordinary observed lifecycle state: while the node is merely dark (stale heartbeat, but
 * not yet dark past {@code nodeDarkTimeout + placementGracePeriod}), the run is left alone even if
 * its last-known observation is stuck mid-lifecycle -- the same "don't relocate off a merely-dark
 * node" safety property {@code DaemonSetReconciler} now also has. Once the node has been dark that
 * long, though, {@link #isGenuinelyGone} treats the run as lost -- retried if {@code backoffLimit}
 * allows, permanently failed otherwise -- via the same {@link #retryOrFail} path a {@code FAILED}
 * observation already takes. Without this, a run whose node vanished for good (not merely
 * partitioned) would sit forever in whatever non-terminal state its last heartbeat reported, with
 * no path back to health at all.
 *
 * <p><b>Known, documented gap, not an oversight</b>: {@link #buildCandidates} folds ordinary
 * deployment replicas' tenant occupancy into its own Tier 2/3 isolation check (a Job must not land
 * on a node already running a different tenant's deployment replica), but the reverse does not hold
 * -- {@link DeploymentReconciler#buildCandidates} was deliberately left untouched, to keep nothing
 * existing needing to change, so a deployment replica can still be placed onto a node already
 * running a different tenant's Job. Closing this symmetrically means editing {@code
 * DeploymentReconciler}, which every other choice in this design avoided; worth revisiting if Job
 * workloads ever see real Tier 2/3 multi-tenant use.
 */
public final class JobReconciler {

  private static final Logger log = LoggerFactory.getLogger(JobReconciler.class);

  /**
   * Matches {@link DeploymentReconciler#DEFAULT_NODE_DARK_TIMEOUT} exactly -- the two must agree
   * for the same reason theirs does: a node's own darkness is a cluster-wide fact, not a
   * per-reconciler opinion.
   */
  public static final Duration DEFAULT_NODE_DARK_TIMEOUT =
      DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT;

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;
  private final Duration nodeDarkTimeout;
  private final Duration placementGracePeriod;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public JobReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public JobReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(
        store,
        scheduler,
        mutations,
        DEFAULT_NODE_DARK_TIMEOUT,
        DEFAULT_NODE_DARK_TIMEOUT,
        Clock.systemUTC());
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public JobReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      Clock clock) {
    this(
        store,
        scheduler,
        mutations,
        nodeDarkTimeout,
        placementGracePeriod,
        clock,
        ArtifactResolver.localOnly());
  }

  public JobReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      Clock clock,
      ArtifactResolver artifactResolver) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.placementGracePeriod = placementGracePeriod;
    this.clock = clock;
    this.artifactResolver = artifactResolver;
  }

  public void reconcileOnce() {
    Set<String> jobNames = new HashSet<>();
    for (JobSpec spec : store.listJobSpecs()) {
      jobNames.add(spec.name());
    }

    // A job no longer in desired state: every one of its runs is stale.
    for (JobRun run : store.listJobRuns()) {
      if (!jobNames.contains(run.jobName())) {
        mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
      }
    }

    for (JobSpec spec : store.listJobSpecs()) {
      reconcileJob(spec);
    }
  }

  private void reconcileJob(JobSpec spec) {
    if (store.getJobPhase(spec.name()).isPresent()) {
      return; // already SUCCEEDED or FAILED -- terminal, nothing more to do
    }

    List<JobRun> runs = store.listJobRunsFor(spec.name());
    // Convergence from an arbitrary starting state: exactly one run should exist per non-terminal
    // job. More than one here means a prior tick crashed between placing a retry and removing its
    // predecessor (see placeAttempt's own ordering note) -- only the highest-numbered attempt is
    // current, every other one is stale and gets cleaned up unconditionally, the same "scale-down
    // is a desired-state edit only" posture DeploymentReconciler's own convergence already has.
    Optional<JobRun> current = runs.stream().max(Comparator.comparingInt(JobRun::attempt));
    for (JobRun run : runs) {
      if (current.isEmpty() || run.attempt() != current.get().attempt()) {
        mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
      }
    }

    if (current.isPresent()) {
      reconcileCurrentRun(spec, current.get());
      return;
    }

    placeAttempt(spec, 0, clock.instant());
  }

  private void reconcileCurrentRun(JobSpec spec, JobRun run) {
    if (spec.activeDeadline().isPresent()
        && Duration.between(run.startedAt(), clock.instant()).compareTo(spec.activeDeadline().get())
            > 0) {
      log.warn(
          "job {} exceeded its activeDeadline of {}; marking permanently failed",
          spec.name(),
          spec.activeDeadline().get());
      mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
      mutations.propose(new StateMutation.PutJobPhase(spec.name(), JobPhase.FAILED));
      return;
    }

    // A node genuinely gone (dark well past the ordinary nodeDarkTimeout -- see isGenuinelyGone)
    // is treated as a lost attempt outright, in preference to whatever lifecycle state its last
    // heartbeat happened to freeze at -- see the class javadoc's own "Placement safety" note. A
    // node that is merely dark (or hasn't heartbeated at all, e.g. right after a leader failover)
    // falls through to the observationFor check below, which waits rather than acts, the same
    // safety property DaemonSetReconciler now also has.
    if (isGenuinelyGone(run.nodeId(), clock.instant())) {
      log.warn(
          "job {} attempt {} was running on node {}, which has been unreachable for longer than"
              + " the placement grace period; treating the attempt as lost",
          spec.name(),
          run.attempt(),
          run.nodeId());
      retryOrFail(spec, run);
      return;
    }

    Optional<InstanceObservation> observation = observationFor(run);
    if (observation.isEmpty()) {
      return; // not yet placed/heartbeated from the run's own node; wait for the next tick
    }
    String state = observation.get().lifecycleState();
    if ("COMPLETED".equals(state)) {
      mutations.propose(new StateMutation.PutJobPhase(spec.name(), JobPhase.SUCCEEDED));
      mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
      return;
    }
    if (!"FAILED".equals(state)) {
      return; // still installing/resolving/starting/active/stopping -- nothing to do this tick
    }
    retryOrFail(spec, run);
  }

  /**
   * Shared by a {@code FAILED} observation and a genuinely-gone node (see {@link
   * #reconcileCurrentRun}): retries with a fresh attempt if {@code backoffLimit} allows, otherwise
   * marks the job permanently failed. Either way {@code run}'s own entry is removed.
   */
  private void retryOrFail(JobSpec spec, JobRun run) {
    int nextAttempt = run.attempt() + 1;
    if (nextAttempt >= spec.backoffLimit()) {
      log.warn(
          "job {} exhausted its backoffLimit of {}; marking permanently failed",
          spec.name(),
          spec.backoffLimit());
      mutations.propose(new StateMutation.PutJobPhase(spec.name(), JobPhase.FAILED));
      mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
      return;
    }
    // Place the retry BEFORE removing the failed attempt (never the other way around): a crash
    // between these two mutations must never leave zero runs on record for a non-terminal job --
    // reconcileJob's own arbitrary-starting-state cleanup above already knows how to reconcile
    // two coexisting runs down to one, but has no way to recover a lost attempt counter if both
    // were ever removed first. startedAt carries forward unchanged -- see JobRun's own javadoc.
    placeAttempt(spec, nextAttempt, run.startedAt());
    mutations.propose(new StateMutation.RemoveJobRun(run.jobName(), run.attempt()));
  }

  private void placeAttempt(JobSpec spec, int attempt, Instant activeSince) {
    ModuleArtifact artifact;
    try {
      artifact = artifactResolver.resolve(spec.artifactPath(), spec.moduleId());
    } catch (RuntimeException e) {
      log.warn(
          "job {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      return;
    }
    // Matches DeploymentReconciler's own artifact-hash check exactly: an artifact silently
    // swapped out from under a running job name is refused, not silently followed.
    if (spec.artifactSha256().isPresent()
        && !spec.artifactSha256().get().equals(artifact.sha256())) {
      log.warn(
          "job {} artifact at {} no longer matches the hash recorded at admission (expected {},"
              + " found {}) -- refusing to place further attempts until the spec is resubmitted",
          spec.name(),
          spec.artifactPath(),
          spec.artifactSha256().get(),
          artifact.sha256());
      return;
    }
    ModuleDescriptor descriptor = artifact.descriptor();

    try {
      List<NodeCandidate> candidates = buildCandidates(spec.name());
      String nodeId =
          scheduler.place(
              spec.name(),
              attempt,
              descriptor.isolationTier(),
              descriptor.resourceRequest(),
              spec.placement().antiAffinityAcrossNodes(),
              spec.tenantId(),
              spec.placement().requiredNodeLabels().orElse(Set.of()),
              candidates);
      mutations.propose(
          new StateMutation.PutJobRun(
              new JobRun(
                  spec.name(),
                  attempt,
                  nodeId,
                  spec.moduleId(),
                  spec.artifactPath(),
                  activeSince)));
    } catch (GimleSchedulingException e) {
      // Left unplaced; the next tick retries from the same full snapshot -- the same
      // level-triggered "a missed placement is indistinguishable from one being retried" property
      // DeploymentReconciler's own missing-index placement already relies on.
      log.warn("could not place job {} attempt {}: {}", spec.name(), attempt, e.getMessage());
    }
  }

  /**
   * True only once the run's own node heartbeat reports an observation for THIS {@code (jobName,
   * attempt)} identity -- mirrors {@link DeploymentReconciler#isReady}'s own node- heartbeat lookup
   * exactly, just matched by attempt instead of instanceIndex (they're the same wire field, {@code
   * AssignedInstance}/{@code InstanceObservation}'s {@code instanceIndex}, see {@code
   * ApiServer.handleAssignments}'s own job-run mapping).
   */
  private Optional<InstanceObservation> observationFor(JobRun run) {
    return store
        .getNodeHeartbeat(run.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .filter(
            obs ->
                obs.deploymentName().equals(run.jobName()) && obs.instanceIndex() == run.attempt())
        .findFirst();
  }

  /**
   * Mirrors {@link DeploymentReconciler#buildCandidates} exactly, built from {@link JobRun}s
   * instead of {@link InstanceAssignment}s -- see this class's own javadoc for the one-directional
   * tenant-isolation gap this introduces.
   */
  private List<NodeCandidate> buildCandidates(String jobName) {
    Set<String> nodesAlreadyRunningThisJob = new HashSet<>();
    Map<String, Set<String>> tenantsByNode = new HashMap<>();
    for (JobRun run : store.listJobRuns()) {
      if (run.jobName().equals(jobName)) {
        nodesAlreadyRunningThisJob.add(run.nodeId());
      }
      store
          .getJobSpec(run.jobName())
          .flatMap(JobSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(run.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }
    // Fold in ordinary deployment replicas' tenant occupancy too -- Tier 2/3 isolation must hold
    // across every workload kind sharing a node, not just within Job's own bookkeeping.
    for (InstanceAssignment assignment : store.listAssignments()) {
      store
          .getDeployment(assignment.deploymentName())
          .flatMap(DeploymentSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }

    Instant now = clock.instant();
    List<NodeCandidate> candidates = new ArrayList<>();
    for (NodeRegistration registration : store.listNodeRegistrations()) {
      Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(registration.nodeId());
      if (heartbeat.isEmpty()) {
        continue; // no capacity report yet; not a placement candidate until it heartbeats
      }
      if (hasGoneDark(heartbeat.get(), now)) {
        continue; // see DeploymentReconciler.buildCandidates's own identical comment
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisJob.contains(registration.nodeId()),
              tenantsByNode.getOrDefault(registration.nodeId(), Set.of()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }

  /**
   * True once {@code nodeId}'s own heartbeat has been stale for longer than {@code nodeDarkTimeout
   * + placementGracePeriod} -- a materially longer bar than {@link #hasGoneDark}'s own {@code
   * nodeDarkTimeout}, which only ever governs placement-candidate eligibility for a *new* run, not
   * whether an *existing* one should be given up on. A node with no heartbeat on record at all
   * returns {@code false}: this run could only have been placed on a node that had heartbeated at
   * the time, so an outright-missing heartbeat here means something other than ordinary darkness
   * (most likely a leader failover this replica's own leader-local heartbeat map hasn't recovered
   * from yet), which this reconciler doesn't try to distinguish from genuine loss -- it simply
   * waits, the same as it already does for "not yet heartbeated since placement".
   */
  private boolean isGenuinelyGone(String nodeId, Instant now) {
    return store
        .getNodeHeartbeat(nodeId)
        .map(
            observed ->
                Duration.between(observed.receivedAt(), now)
                        .compareTo(nodeDarkTimeout.plus(placementGracePeriod))
                    > 0)
        .orElse(false);
  }
}
