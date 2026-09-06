package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.node.NodeFreshness;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.NodeCandidateSource;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures a {@link DaemonSetAssignment} exists for every node currently eligible for a {@link
 * DaemonSetSpec} -- one per node, cluster-wide, not a stored replica count the way {@link
 * DeploymentReconciler} reads {@code spec.replicas()}. "How many" is recomputed from live node
 * state every tick via {@link Scheduler#eligibleNodes}: the same five-step filter chain {@code
 * place} applies -- tier, cordon, anti-affinity (always a no-op here, see {@link DaemonSetSpec}'s
 * own javadoc), tenant isolation (bypassed entirely when {@link DaemonSetSpec#tolerateAllTaints} is
 * set -- see its own javadoc), required labels -- minus the final bin-packing pick, since there is
 * nothing to pick: every survivor gets an assignment, and {@code Scheduler.place} itself is never
 * called.
 *
 * <p>Level-triggered, following the exact same convergence shape {@link DeploymentReconciler} and
 * {@link JobReconciler} already establish: every tick re-derives the full desired set from the
 * current snapshot rather than reacting to what changed since last tick.
 *
 * <p><b>Placement safety</b>: a node whose only reason for falling out of {@code eligibleNodeIds}
 * is that the store cannot currently vouch for it keeps its existing assignment -- see {@link
 * #isUnconfirmedRatherThanGone}. That covers both shapes the uncertainty takes. A node whose
 * heartbeat has merely gone stale (past {@code nodeDarkTimeout}, the same darkness {@link
 * #buildCandidates} already excludes from placement) is held until that darkness has lasted {@code
 * nodeDarkTimeout + placementGracePeriod}; without it, an ordinary bidirectional network partition
 * would tear down a perfectly healthy node's assignment the instant the heartbeat goes stale, even
 * though the node's own agent (unaware of the control plane's view) keeps supervising that same
 * worker the whole time -- the assignment is simply gone from the control plane's own bookkeeping
 * by the time the partition heals, and the agent tears down a worker that never needed to move. A
 * node with <em>no</em> heartbeat on record at all is held for as long as {@link NodeFreshness}
 * says the store has not yet had the opportunity to hear from it: heartbeats live only on whichever
 * store replica is currently leader and are never replicated, so an election leaves the new leader
 * holding nothing for any node, and reading that emptiness as a fact about the nodes would tear
 * every DaemonSet in the cluster down the moment leadership moved. Any other ineligibility reason
 * (cordon, relabeling, a tier/label mismatch) still evicts immediately -- those are deliberate
 * operator actions, not an ambiguous "is the node even still there" signal, so there's nothing to
 * wait out.
 *
 * <p><b>The published desired count</b> is the number of nodes this DaemonSet should currently
 * occupy: the eligible ones plus the ones held above. Counting only the eligible ones made it
 * disagree with the very assignments this same tick chose to keep, so a reader subtracting placed
 * from desired saw a negative shortfall. It is written after this tick's evictions and rollout step
 * have been decided and before any new placement, which is the one ordering in which a tick that
 * aborts partway can only ever leave desired at or above what is actually placed.
 *
 * <p>Rolling updates are a direct duplicate of {@link DeploymentReconciler#handleRollingUpdate}'s
 * state machine, keyed by {@code nodeId} instead of {@code instanceIndex} via {@link
 * StateStore#addRollingDaemonSetNode}/{@code removeRollingDaemonSetNode}/{@code
 * getRollingDaemonSetNodes} -- deliberately duplicated rather than generalizing {@code
 * DeploymentReconciler} over a key-type parameter: this codebase's own convention prefers direct,
 * readable duplication over an abstraction that would only ever have two call sites. Up to the
 * DaemonSet's effective {@link com.gimle.mimir.manifest.DisruptionBudget#maxUnavailable} nodes
 * migrate concurrently, same as {@code DeploymentReconciler}'s own continuous top-up model.
 *
 * <p><b>Crash-loop backoff</b>: {@link #isReady} only ever answers "is this node caught up and
 * ready" -- an assignment whose module crashed on every restart otherwise sat there forever, since
 * nothing but a rolling-update version mismatch or an eligibility change ever removed an existing
 * assignment. {@link WorkloadCrashLoopBackoff} (this class's own DaemonSet-keyed instance, keyed by
 * {@code nodeId} rather than an instance index) closes that gap the same shape {@link
 * HealthReconciler} already established for Deployment: back off, then release the stale assignment
 * so the placement pass below re-adds a fresh one to the very same (still-eligible) node -- there
 * is nowhere else for a DaemonSet assignment to go -- or give up permanently once the restart
 * budget is exhausted.
 */
public final class DaemonSetReconciler {

  private static final Logger log = LoggerFactory.getLogger(DaemonSetReconciler.class);

  /** Matches {@link DeploymentReconciler#DEFAULT_NODE_DARK_TIMEOUT} exactly -- see its own note. */
  public static final Duration DEFAULT_NODE_DARK_TIMEOUT =
      DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT;

  private static final String WORKLOAD_KIND = "DaemonSet";

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;
  private final Duration nodeDarkTimeout;
  private final Duration placementGracePeriod;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;
  private final WorkloadCrashLoopBackoff crashLoopBackoff;
  private final NodeCandidateSource candidateSource;
  private final NodeFreshness freshness;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public DaemonSetReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public DaemonSetReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(
        store,
        scheduler,
        mutations,
        DEFAULT_NODE_DARK_TIMEOUT,
        DEFAULT_NODE_DARK_TIMEOUT,
        Clock.systemUTC());
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public DaemonSetReconciler(
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

  public DaemonSetReconciler(
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
    this.candidateSource = new NodeCandidateSource(store, nodeDarkTimeout, clock);
    this.crashLoopBackoff = new WorkloadCrashLoopBackoff(store);
    // Same threshold the candidate source judges darkness by, so the two can never disagree about
    // whether a node is answering.
    this.freshness = new NodeFreshness(nodeDarkTimeout);
  }

  public void reconcileOnce() {
    // Tenant-scoped identity, not the bare name alone -- see DeploymentReconciler's own identical
    // sweep for why: two different tenants' own identically-named DaemonSet are distinct
    // desired-state entries.
    Set<Map.Entry<Optional<String>, String>> daemonSetIdentities = new HashSet<>();
    for (DaemonSetSpec spec : store.listDaemonSetSpecs()) {
      daemonSetIdentities.add(Map.entry(spec.tenantId(), spec.name()));
    }

    // A daemonset no longer in desired state: every one of its assignments is stale. One batch
    // for the whole sweep -- nothing below reads these removals back before the per-daemonset
    // passes run.
    List<StateMutation> staleRemovals = new ArrayList<>();
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignments()) {
      if (!daemonSetIdentities.contains(
          Map.entry(assignment.tenantId(), assignment.daemonSetName()))) {
        staleRemovals.add(
            new StateMutation.RemoveDaemonSetAssignment(
                assignment.tenantId(), assignment.daemonSetName(), assignment.nodeId()));
      }
    }
    mutations.proposeAll(staleRemovals);

    for (DaemonSetSpec spec : store.listDaemonSetSpecs()) {
      try {
        reconcileDaemonSet(spec);
      } catch (RuntimeException e) {
        // One daemonset's failure (e.g. a GimleRaftException from mutations.propose during a
        // store leader-election gap) must never abort the rest of this tick's daemonsets -- the
        // next tick retries this one from the same full snapshot.
        log.warn("reconcile of daemonset {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileDaemonSet(DaemonSetSpec spec) {
    ModuleArtifact artifact;
    try {
      artifact = artifactResolver.resolve(spec.artifactPath(), spec.moduleId(), spec.vessel());
    } catch (RuntimeException e) {
      log.warn(
          "daemonset {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      return;
    }
    // Matches DeploymentReconciler's own artifact-hash check exactly: an artifact silently
    // swapped out from under a running daemonset name is refused, not silently followed.
    if (spec.artifactSha256().isPresent()
        && !spec.artifactSha256().get().equals(artifact.sha256())) {
      log.warn(
          "daemonset {} artifact at {} no longer matches the hash recorded at admission (expected"
              + " {}, found {}) -- refusing to place new assignments until the spec is"
              + " resubmitted",
          spec.name(),
          spec.artifactPath(),
          spec.artifactSha256().get(),
          artifact.sha256());
      return;
    }
    ModuleDescriptor descriptor = artifact.descriptor();

    Instant now = clock.instant();
    Instant observingSince = store.nodeObservationWindowStart();

    Set<String> eligibleNodeIds =
        scheduler
            .eligibleNodes(
                descriptor.isolationTier(),
                spec.placement().antiAffinityAcrossNodes(),
                spec.tenantId(),
                spec.placement().requiredNodeLabels().orElse(Set.of()),
                spec.tolerateAllTaints(),
                buildCandidates())
            .stream()
            .map(NodeCandidate::nodeId)
            .collect(Collectors.toSet());

    // Scale-down: an assignment on a node that fell out of eligibility (cordoned, removed,
    // relabeled) is removed immediately -- a desired-state edit only, mirroring
    // DeploymentReconciler's own scale-down pass exactly. The agent's own stop()/StopModule drain
    // timing owns teardown, not this reconciler. The one exception is a node that fell out of
    // eligibility purely because the store cannot currently vouch for it: see
    // isUnconfirmedRatherThanGone and the class javadoc's own "Placement safety" note for why that
    // case waits instead.
    Set<String> heldNodeIds = new HashSet<>();
    List<StateMutation> evictions = new ArrayList<>();
    for (DaemonSetAssignment assignment :
        store.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name())) {
      if (!eligibleNodeIds.contains(assignment.nodeId())) {
        if (isUnconfirmedRatherThanGone(assignment.nodeId(), now, observingSince)) {
          heldNodeIds.add(assignment.nodeId());
          continue;
        }
        log.warn(
            "daemonset {} node {} is no longer eligible ({}); releasing its assignment -- this"
                + " daemonset is now short a replica versus its own eligible-node count until a"
                + " replacement node becomes eligible",
            spec.name(),
            assignment.nodeId(),
            ineligibilityReason(assignment.nodeId(), now, observingSince));
        evictions.add(
            new StateMutation.RemoveDaemonSetAssignment(
                spec.tenantId(), spec.name(), assignment.nodeId()));
        continue;
      }
      // Still eligible: see class javadoc's "Crash-loop backoff" note.
      if (crashLoopBackoff.isPermanentlyFailed(
          WORKLOAD_KIND, spec.name(), assignment.nodeId(), spec.tenantId())) {
        continue; // stuck here forever; nothing left to attempt.
      }
      if (isCrashLooping(assignment)) {
        handleCrashLoop(spec, assignment, now, evictions);
        continue;
      }
      if (isReady(assignment)) {
        crashLoopBackoff
            .handleHealthyObserved(WORKLOAD_KIND, spec.name(), assignment.nodeId(), spec.tenantId())
            .ifPresent(evictions::add);
      }
    }
    // Flushed before handleRollingUpdate runs -- its own assignment scans must see these.
    mutations.proposeAll(evictions);

    handleRollingUpdate(spec, descriptor, eligibleNodeIds, now, observingSince);

    // The nodes this DaemonSet should be occupying right now -- see the class javadoc's own "The
    // published desired count" note for why the held ones count and why this is written here.
    // Level-triggered means recomputing it from scratch every tick, not re-proposing it every
    // tick: only write when it actually moved, the same restraint LimitRangeReconciler's own
    // violation flag applies.
    Set<String> desiredNodeIds = new HashSet<>(eligibleNodeIds);
    desiredNodeIds.addAll(heldNodeIds);
    int desiredCount = desiredNodeIds.size();
    if (store
        .getDaemonSetDesiredCount(spec.tenantId(), spec.name())
        .map(c -> c != desiredCount)
        .orElse(true)) {
      mutations.propose(
          new StateMutation.PutDaemonSetDesiredCount(spec.tenantId(), spec.name(), desiredCount));
    }

    // Re-read: scale-down and/or the rolling-update step above may have just removed an entry.
    Set<String> assignedNodeIds = new HashSet<>();
    for (DaemonSetAssignment assignment :
        store.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name())) {
      assignedNodeIds.add(assignment.nodeId());
    }

    // One batch for the whole burst: a fresh daemonset landing on N eligible nodes pays one
    // consensus round and one WAL fsync instead of one per node.
    List<StateMutation> placements = new ArrayList<>();
    for (String nodeId : eligibleNodeIds) {
      if (assignedNodeIds.contains(nodeId)) {
        continue;
      }
      placements.add(
          new StateMutation.PutDaemonSetAssignment(
              new DaemonSetAssignment(
                  spec.name(), nodeId, spec.moduleId(), spec.artifactPath(), spec.tenantId())));
    }
    mutations.proposeAll(placements);
  }

  /**
   * Direct duplicate of {@link DeploymentReconciler#handleRollingUpdate}, keyed by {@code nodeId}
   * instead of {@code instanceIndex} -- see this class's own javadoc for why duplicated rather than
   * shared. Tops up the in-flight node set to the DaemonSet's effective {@code maxUnavailable}
   * every tick: checks every already-in-flight node for readiness (clearing it once its replacement
   * has landed and reported ready), then, if budget remains, starts new migrations for the
   * lowest-{@code nodeId} (lexicographic -- there is no natural ordering across nodes the way there
   * is across integer indices, so this is simply a stable, deterministic tie-break) mismatches not
   * already in flight, removing each one's stale assignment so the caller's ordinary missing-node
   * placement logic above re-places it with the current spec's {@code moduleId}.
   */
  private void handleRollingUpdate(
      DaemonSetSpec spec,
      ModuleDescriptor descriptor,
      Set<String> eligibleNodeIds,
      Instant now,
      Instant observingSince) {
    int maxUnavailable = spec.effectiveDisruptionBudget().maxUnavailable();
    Set<String> inFlight =
        new HashSet<>(store.getRollingDaemonSetNodes(spec.tenantId(), spec.name()));
    // Accumulated for one flush at method end: the in-flight set is tracked locally, nothing here
    // reads its own proposals back, and the caller's re-read runs only after this returns.
    List<StateMutation> changes = new ArrayList<>();

    for (String nodeId : Set.copyOf(inFlight)) {
      Optional<DaemonSetAssignment> current =
          store.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name()).stream()
              .filter(a -> a.nodeId().equals(nodeId))
              .findFirst();
      if (current.isPresent() && isReady(current.get())) {
        changes.add(
            new StateMutation.RemoveRollingDaemonSetNode(spec.tenantId(), spec.name(), nodeId));
        inFlight.remove(nodeId);
      } else if (current.isEmpty()
          && !eligibleNodeIds.contains(nodeId)
          && !isUnconfirmedRatherThanGone(nodeId, now, observingSince)) {
        // The scale-down race, node-keyed equivalent of DeploymentReconciler's own: the node fell
        // out of eligibility (cordoned, removed, relabeled) while its old assignment was already
        // removed for migration, so a replacement will now never come. A node the store simply
        // cannot vouch for yet is not that case and keeps its marker -- abandoning the migration
        // on an unconfirmed absence would leave the node running the old version with nothing
        // tracking that it is mid-rollout.
        changes.add(
            new StateMutation.RemoveRollingDaemonSetNode(spec.tenantId(), spec.name(), nodeId));
        inFlight.remove(nodeId);
      }
    }

    if (inFlight.size() >= maxUnavailable) {
      mutations.proposeAll(changes);
      return;
    }

    store.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name()).stream()
        .filter(assignment -> isStale(assignment, spec))
        .filter(assignment -> !inFlight.contains(assignment.nodeId()))
        .sorted(Comparator.comparing(DaemonSetAssignment::nodeId))
        .limit(maxUnavailable - inFlight.size())
        .forEach(
            mismatched -> {
              changes.add(
                  new StateMutation.RemoveDaemonSetAssignment(
                      spec.tenantId(), spec.name(), mismatched.nodeId()));
              changes.add(
                  new StateMutation.AddRollingDaemonSetNode(
                      spec.tenantId(), spec.name(), mismatched.nodeId()));
              log.info(
                  "daemonset {} node {} is on an old module version; rolling it forward",
                  spec.name(),
                  mismatched.nodeId());
            });
    mutations.proposeAll(changes);
  }

  /**
   * Mirrors {@link DeploymentReconciler#isStale} exactly, including comparing {@code artifactPath}
   * alongside {@code moduleId}: a re-applied manifest with the same {@code moduleId} but a patched
   * jar at a new {@code artifactPath} must actually roll out, not just look like it did.
   */
  private static boolean isStale(DaemonSetAssignment assignment, DaemonSetSpec spec) {
    return !assignment.moduleId().equals(spec.moduleId())
        || !assignment.artifactPath().equals(spec.artifactPath());
  }

  /**
   * True only once the node's own heartbeat reports THIS daemonset as both {@code ready} and
   * actually running {@code assignment}'s own {@code moduleId} -- mirrors {@link
   * DeploymentReconciler#isReady} exactly, matched by {@code daemonSetName}/{@code nodeId} instead
   * of {@code deploymentName}/{@code instanceIndex}. A DaemonSet assignment's own heartbeat
   * observation reuses {@code AssignedInstance}'s existing wire shape via the agent's own
   * assignments endpoint the same way a Job run does (see {@code JobReconciler}'s own javadoc) --
   * {@code deploymentName} carries the daemonset's name, {@code instanceIndex} is always {@code 0}
   * (there is exactly one instance per node, so no second index is ever needed).
   */
  private boolean isReady(DaemonSetAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .anyMatch(
            obs ->
                obs.deploymentName().equals(assignment.daemonSetName())
                    && obs.instanceIndex() == 0
                    && obs.tenantId().equals(assignment.tenantId())
                    && obs.moduleId().equals(assignment.moduleId())
                    && obs.ready());
  }

  /**
   * Persists the {@link WorkloadCrashLoopBackoff} verdict for a crash-looping node and, if it says
   * to act now, either releases the stale assignment (the very next loop below re-adds a fresh one
   * to this same node, since it's still in {@code eligibleNodeIds}) or gives up on it permanently
   * -- mirrors {@link HealthReconciler#handleUnhealthy}'s own three outcomes exactly. {@code
   * evictions} is the caller's own batch, flushed once after the whole per-node scan completes.
   */
  private void handleCrashLoop(
      DaemonSetSpec spec,
      DaemonSetAssignment assignment,
      Instant now,
      List<StateMutation> evictions) {
    WorkloadCrashLoopBackoff.Evaluation evaluation =
        crashLoopBackoff.handleFailureObserved(
            WORKLOAD_KIND, spec.name(), assignment.nodeId(), spec.tenantId(), now);
    evictions.add(evaluation.stateMutation());
    if (evaluation.permanentlyFailed()) {
      log.error(
          "daemonset {} node {} exhausted its restart budget; giving up on rescheduling it",
          spec.name(),
          assignment.nodeId());
      evictions.add(
          new StateMutation.AppendInstanceEvent(
              spec.tenantId(),
              new InstanceEvent(
                  UUID.randomUUID().toString(),
                  spec.name(),
                  0,
                  InstanceEventKind.TRANSITION_FAILED,
                  "node "
                      + assignment.nodeId()
                      + " exhausted its restart budget; giving up on rescheduling it",
                  Optional.empty(),
                  clock.millis())));
    } else if (evaluation.shouldRemoveAssignmentNow()) {
      log.warn(
          "daemonset {} node {} crash-looped; releasing its assignment for re-placement",
          spec.name(),
          assignment.nodeId());
      evictions.add(
          new StateMutation.RemoveDaemonSetAssignment(
              spec.tenantId(), spec.name(), assignment.nodeId()));
    }
  }

  /**
   * True once the node's own heartbeat reports THIS daemonset's instance as {@code FAILED} --
   * distinct from merely not-yet-ready (still starting), which {@link #isReady} alone cannot tell
   * apart. See class javadoc's "Crash-loop backoff" note.
   */
  private boolean isCrashLooping(DaemonSetAssignment assignment) {
    return findObservation(assignment)
        .filter(obs -> "FAILED".equals(obs.lifecycleState()))
        .isPresent();
  }

  private Optional<InstanceObservation> findObservation(DaemonSetAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .filter(
            obs ->
                obs.deploymentName().equals(assignment.daemonSetName())
                    && obs.instanceIndex() == 0
                    && obs.tenantId().equals(assignment.tenantId()))
        .findFirst();
  }

  /**
   * Every candidate's {@code alreadyRunsThisDeployment} flag is {@code false} here -- meaningless
   * for a DaemonSet, since {@code antiAffinityAcrossNodes} is always {@code false} too (see {@link
   * DaemonSetSpec}'s own javadoc), so nothing ever reads it.
   */
  private List<NodeCandidate> buildCandidates() {
    return candidateSource.candidates(Set.of());
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }

  /**
   * A human-legible reason a node just fell out of eligibility, for the eviction log line above --
   * the fastest-to-check, most-specific cause first. Heartbeat state is checked ahead of {@link
   * StoreReader#isNodeCordoned}: an operator who cordoned a node already knows why; an operator
   * whose node went dark does not, and that's exactly the diagnostic gap this exists to close.
   */
  private String ineligibilityReason(String nodeId, Instant now, Instant observingSince) {
    Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(nodeId);
    if (heartbeat.isEmpty()) {
      return "the store has heard nothing from it since it began observing at " + observingSince;
    }
    if (hasGoneDark(heartbeat.get(), now)) {
      return "no longer confirmed by a heartbeat";
    }
    if (store.isNodeCordoned(nodeId)) {
      return "node is cordoned";
    }
    return "node no longer matches this daemonset's placement requirements";
  }

  /**
   * True when {@code nodeId}'s absence from {@code eligibleNodeIds} is not yet a fact about the
   * node -- the whole point being that a read which came back empty is not the same claim as a node
   * that is gone. Two cases, both temporary by construction, both covered in the class javadoc's
   * "Placement safety" note: a heartbeat that has gone stale but not yet for {@code nodeDarkTimeout
   * + placementGracePeriod}, and no heartbeat at all while {@link NodeFreshness} still reports the
   * store has not had the opportunity to hear from this node -- which is exactly the window
   * following a store leader election, since heartbeats are leader-local and never replicated.
   */
  private boolean isUnconfirmedRatherThanGone(String nodeId, Instant now, Instant observingSince) {
    Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(nodeId);
    if (heartbeat.isEmpty()) {
      return !freshness.hasGoneDark(true, heartbeat, observingSince, now);
    }
    return hasGoneDark(heartbeat.get(), now)
        && Duration.between(heartbeat.get().receivedAt(), now)
                .compareTo(nodeDarkTimeout.plus(placementGracePeriod))
            <= 0;
  }
}
