package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
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
 * own javadoc), tenant isolation, required labels -- minus the final bin-packing pick, since there
 * is nothing to pick: every survivor gets an assignment, and {@code Scheduler.place} itself is
 * never called.
 *
 * <p>Level-triggered, following the exact same convergence shape {@link DeploymentReconciler} and
 * {@link JobReconciler} already establish: every tick re-derives the full desired set from the
 * current snapshot rather than reacting to what changed since last tick.
 *
 * <p><b>Placement safety</b>: a node whose only reason for falling out of {@code eligibleNodeIds}
 * is a stale heartbeat (past {@code nodeDarkTimeout}, the same darkness {@link #buildCandidates}
 * already excludes from placement) keeps its existing assignment until that darkness has lasted
 * {@code nodeDarkTimeout + placementGracePeriod} -- see {@link #isMerelyDarkWithinGracePeriod}.
 * Without this, an ordinary bidirectional network partition would have this reconciler tear down a
 * perfectly healthy node's assignment the instant the heartbeat goes stale, even though the node's
 * own agent (unaware of the control plane's view) keeps supervising that same worker the whole time
 * -- the assignment is simply gone from the control plane's own bookkeeping by the time the
 * partition heals, and the agent tears down a worker that never needed to move. Any other
 * ineligibility reason (cordon, relabeling, a tier/label mismatch) still evicts immediately,
 * exactly as before this grace period existed -- those are deliberate operator actions, not an
 * ambiguous "is the node even still there" signal, so there's nothing to wait out.
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
    this.crashLoopBackoff = new WorkloadCrashLoopBackoff(store);
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

    Set<String> eligibleNodeIds =
        scheduler
            .eligibleNodes(
                descriptor.isolationTier(),
                spec.placement().antiAffinityAcrossNodes(),
                spec.tenantId(),
                spec.placement().requiredNodeLabels().orElse(Set.of()),
                buildCandidates(spec.name()))
            .stream()
            .map(NodeCandidate::nodeId)
            .collect(Collectors.toSet());

    // Scale-down: an assignment on a node that fell out of eligibility (cordoned, removed,
    // relabeled) is removed immediately -- a desired-state edit only, mirroring
    // DeploymentReconciler's own scale-down pass exactly. The agent's own stop()/StopModule drain
    // timing owns teardown, not this reconciler. The one exception is a node that fell out of
    // eligibility purely because it's dark: see isMerelyDarkWithinGracePeriod and the class
    // javadoc's own "Placement safety" note for why that case waits instead.
    Instant now = clock.instant();
    List<StateMutation> evictions = new ArrayList<>();
    for (DaemonSetAssignment assignment :
        store.listDaemonSetAssignmentsFor(spec.tenantId(), spec.name())) {
      if (!eligibleNodeIds.contains(assignment.nodeId())) {
        if (isMerelyDarkWithinGracePeriod(assignment.nodeId(), now)) {
          continue;
        }
        log.warn(
            "daemonset {} node {} is no longer eligible ({}); releasing its assignment -- this"
                + " daemonset is now short a replica versus its own eligible-node count until a"
                + " replacement node becomes eligible",
            spec.name(),
            assignment.nodeId(),
            ineligibilityReason(assignment.nodeId(), now));
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

    handleRollingUpdate(spec, descriptor, eligibleNodeIds);

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
      DaemonSetSpec spec, ModuleDescriptor descriptor, Set<String> eligibleNodeIds) {
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
      } else if (current.isEmpty() && !eligibleNodeIds.contains(nodeId)) {
        // The scale-down race, node-keyed equivalent of DeploymentReconciler's own: the node fell
        // out of eligibility (cordoned, removed, relabeled) while its old assignment was already
        // removed for migration, so a replacement will now never come.
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
        .filter(assignment -> !assignment.moduleId().equals(spec.moduleId()))
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
   * Mirrors {@link DeploymentReconciler#buildCandidates} exactly. {@code alreadyRunsThisDeployment}
   * is always {@code false} on every candidate here -- meaningless for a DaemonSet, since {@code
   * antiAffinityAcrossNodes} is always {@code false} too (see {@link DaemonSetSpec}'s own javadoc),
   * so nothing ever reads it.
   */
  private List<NodeCandidate> buildCandidates(String daemonSetName) {
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
              false,
              store.getNodeTaints(registration.nodeId()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }

  /**
   * True when {@code nodeId} is currently dark (excluded from {@code eligibleNodeIds} by {@link
   * #hasGoneDark} alone) but hasn't been dark long enough yet to count as genuinely gone -- see the
   * class javadoc's "Placement safety" note. A node with no heartbeat on record at all returns
   * {@code false} here (never merely dark, so never grace-gated): the only way a {@link
   * DaemonSetAssignment} exists for a node in the first place is that node having heartbeated at
   * placement time, so a heartbeat missing outright, rather than merely stale, means something more
   * unusual happened (e.g. a leader failover this replica's own leader-local heartbeat map hasn't
   * recovered from yet) that this reconciler doesn't try to distinguish from genuine loss --
   * matching the immediate-removal behavior every non-darkness ineligibility reason already gets.
   */
  /**
   * A human-legible reason a node just fell out of eligibility, for the eviction log line above --
   * the fastest-to-check, most-specific cause first. Heartbeat state is checked ahead of {@link
   * StoreReader#isNodeCordoned}: an operator who cordoned a node already knows why; an operator
   * whose node went dark does not, and that's exactly the diagnostic gap this exists to close.
   */
  private String ineligibilityReason(String nodeId, Instant now) {
    Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(nodeId);
    if (heartbeat.isEmpty()) {
      return "no heartbeat on record";
    }
    if (hasGoneDark(heartbeat.get(), now)) {
      return "no longer confirmed by a heartbeat";
    }
    if (store.isNodeCordoned(nodeId)) {
      return "node is cordoned";
    }
    return "node no longer matches this daemonset's placement requirements";
  }

  private boolean isMerelyDarkWithinGracePeriod(String nodeId, Instant now) {
    return store
        .getNodeHeartbeat(nodeId)
        .filter(observed -> hasGoneDark(observed, now))
        .filter(
            observed ->
                Duration.between(observed.receivedAt(), now)
                        .compareTo(nodeDarkTimeout.plus(placementGracePeriod))
                    <= 0)
        .isPresent();
  }
}
