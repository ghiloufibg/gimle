package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures a {@link DaemonSetAssignment} exists for every node currently eligible for a {@link
 * DaemonSetSpec} -- one per node, cluster-wide (priority-3 design doc §4), not a stored replica
 * count the way {@link DeploymentReconciler} reads {@code spec.replicas()}. "How many" is
 * recomputed from live node state every tick via {@link Scheduler#eligibleNodes} (§4b): the same
 * five-step filter chain {@code place} applies -- tier, cordon, anti-affinity (always a no-op here,
 * see {@link DaemonSetSpec}'s own javadoc), tenant isolation, required labels -- minus the final
 * bin-packing pick, since there is nothing to pick: every survivor gets an assignment, and {@code
 * Scheduler.place} itself is never called.
 *
 * <p>Level-triggered, following the exact same convergence shape {@link DeploymentReconciler} and
 * {@link JobReconciler} already establish: every tick re-derives the full desired set from the
 * current snapshot rather than reacting to what changed since last tick.
 *
 * <p>Rolling updates are a direct duplicate of {@link DeploymentReconciler#handleRollingUpdate}'s
 * ~35-line state machine, keyed by {@code nodeId} instead of {@code instanceIndex} via {@link
 * StateStore#putRollingDaemonSetNode}/{@code getRollingDaemonSetNode}/{@code
 * clearRollingDaemonSetNode} -- deliberately duplicated rather than generalizing {@code
 * DeploymentReconciler} over a key-type parameter (design doc §4c): this codebase's own convention
 * prefers direct, readable duplication over an abstraction that would only ever have two call
 * sites.
 */
public final class DaemonSetReconciler {

  private static final Logger log = LoggerFactory.getLogger(DaemonSetReconciler.class);

  /** Matches {@link DeploymentReconciler#DEFAULT_NODE_DARK_TIMEOUT} exactly -- see its own note. */
  public static final Duration DEFAULT_NODE_DARK_TIMEOUT =
      DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT;

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;
  private final Duration nodeDarkTimeout;
  private final Clock clock;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public DaemonSetReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public DaemonSetReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(store, scheduler, mutations, DEFAULT_NODE_DARK_TIMEOUT, Clock.systemUTC());
  }

  public DaemonSetReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Clock clock) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.clock = clock;
  }

  public void reconcileOnce() {
    Set<String> daemonSetNames = new HashSet<>();
    for (DaemonSetSpec spec : store.listDaemonSetSpecs()) {
      daemonSetNames.add(spec.name());
    }

    // A daemonset no longer in desired state: every one of its assignments is stale.
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignments()) {
      if (!daemonSetNames.contains(assignment.daemonSetName())) {
        mutations.propose(
            new StateMutation.RemoveDaemonSetAssignment(
                assignment.daemonSetName(), assignment.nodeId()));
      }
    }

    for (DaemonSetSpec spec : store.listDaemonSetSpecs()) {
      reconcileDaemonSet(spec);
    }
  }

  private void reconcileDaemonSet(DaemonSetSpec spec) {
    ModuleArtifact artifact;
    try {
      artifact = ModuleArtifactReader.read(Path.of(spec.artifactPath()));
    } catch (RuntimeException e) {
      log.warn(
          "daemonset {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      return;
    }
    // Matches DeploymentReconciler's own P2-18 check exactly: an artifact silently swapped out
    // from under a running daemonset name is refused, not silently followed.
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
    // timing owns teardown, not this reconciler.
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignmentsFor(spec.name())) {
      if (!eligibleNodeIds.contains(assignment.nodeId())) {
        mutations.propose(
            new StateMutation.RemoveDaemonSetAssignment(spec.name(), assignment.nodeId()));
      }
    }

    handleRollingUpdate(spec, descriptor);

    // Re-read: scale-down and/or the rolling-update step above may have just removed an entry.
    Set<String> assignedNodeIds = new HashSet<>();
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignmentsFor(spec.name())) {
      assignedNodeIds.add(assignment.nodeId());
    }

    for (String nodeId : eligibleNodeIds) {
      if (assignedNodeIds.contains(nodeId)) {
        continue;
      }
      mutations.propose(
          new StateMutation.PutDaemonSetAssignment(
              new DaemonSetAssignment(spec.name(), nodeId, spec.moduleId(), spec.artifactPath())));
    }
  }

  /**
   * Direct duplicate of {@link DeploymentReconciler#handleRollingUpdate}, keyed by {@code nodeId}
   * instead of {@code instanceIndex} -- see this class's own javadoc for why duplicated rather than
   * shared. If a rollout is already in flight, checks whether the replacement on that node has
   * landed and reported ready, clearing the in-flight marker once it has; otherwise looks for a new
   * version mismatch to start, picking the lowest {@code nodeId} lexicographically (there is no
   * natural ordering across nodes the way there is across integer indices, so this is simply a
   * stable, deterministic tie-break) and removing its stale assignment so the caller's ordinary
   * missing-node placement logic above re-places it with the current spec's {@code moduleId}.
   */
  private void handleRollingUpdate(DaemonSetSpec spec, ModuleDescriptor descriptor) {
    Optional<String> rollingNode = store.getRollingDaemonSetNode(spec.name());
    if (rollingNode.isPresent()) {
      String nodeId = rollingNode.get();
      Optional<DaemonSetAssignment> current =
          store.listDaemonSetAssignmentsFor(spec.name()).stream()
              .filter(a -> a.nodeId().equals(nodeId))
              .findFirst();
      if (current.isPresent() && isReady(current.get())) {
        mutations.propose(new StateMutation.ClearRollingDaemonSetNode(spec.name()));
      }
      return;
    }

    store.listDaemonSetAssignmentsFor(spec.name()).stream()
        .filter(assignment -> !assignment.moduleId().equals(spec.moduleId()))
        .min(Comparator.comparing(DaemonSetAssignment::nodeId))
        .ifPresent(
            mismatched -> {
              mutations.propose(
                  new StateMutation.RemoveDaemonSetAssignment(spec.name(), mismatched.nodeId()));
              mutations.propose(
                  new StateMutation.PutRollingDaemonSetNode(spec.name(), mismatched.nodeId()));
              log.info(
                  "daemonset {} node {} is on an old module version; rolling it forward",
                  spec.name(),
                  mismatched.nodeId());
            });
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
                    && obs.moduleId().equals(assignment.moduleId())
                    && obs.ready());
  }

  /**
   * Mirrors {@link DeploymentReconciler#buildCandidates} exactly, built from {@link
   * DaemonSetAssignment}s instead of {@link InstanceAssignment}s. {@code alreadyRunsThisDeployment}
   * is always {@code false} on every candidate here -- meaningless for a DaemonSet, since {@code
   * antiAffinityAcrossNodes} is always {@code false} too (see {@link DaemonSetSpec}'s own javadoc),
   * so nothing ever reads it.
   */
  private List<NodeCandidate> buildCandidates(String daemonSetName) {
    Map<String, Set<String>> tenantsByNode = new HashMap<>();
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignments()) {
      store
          .getDaemonSetSpec(assignment.daemonSetName())
          .flatMap(DaemonSetSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }
    // Fold in ordinary deployment replicas' tenant occupancy too, the same one-directional fold
    // JobReconciler.buildCandidates already documents for Job -- DeploymentReconciler is
    // deliberately left untouched (design doc §0), so this is not symmetric.
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
              false,
              tenantsByNode.getOrDefault(registration.nodeId(), Set.of()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }
}
