package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
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
 * Ensures an {@link InstanceAssignment} exists for every index {@code 0..replicas-1} of every
 * {@link DeploymentSpec}. Level-triggered: every tick re-derives the full set of assignments a
 * from-scratch run would produce from the current snapshot, rather than reacting to what changed
 * since last tick -- deleting a deployment, scaling it, or a fresh empty store all converge through
 * the exact same code path.
 *
 * <p>Rolling updates (deliberately minimal: no {@code maxSurge}/{@code maxUnavailable} knobs)
 * piggyback on this same convergence loop rather than a parallel mechanism: when the lowest-indexed
 * assignment still on the deployment's old {@code moduleId} is found, {@link #handleRollingUpdate}
 * simply removes it (persisting which index is mid-transition via {@link
 * StateStore#putRollingIndex}) and lets the ordinary missing-index placement logic below re-place
 * it with the current spec's {@code moduleId} -- exactly the same code path a fresh scale-up
 * already uses, so a rolling update needs no dedicated placement logic of its own. One index at a
 * time: no new mismatch is picked up while the current one hasn't reported {@code ready} (checked
 * directly against the node's own heartbeat, the same source {@code HealthReconciler} reads), and
 * this state survives a reconciler restart because it's read back from the persisted {@code
 * rollingIndex} rather than kept only in memory.
 */
public final class DeploymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(DeploymentReconciler.class);

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public DeploymentReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public DeploymentReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
  }

  public void reconcileOnce() {
    Set<String> deploymentNames = new HashSet<>();
    for (DeploymentSpec spec : store.listDeployments()) {
      deploymentNames.add(spec.name());
    }

    // A deployment no longer in desired state: every one of its assignments is stale.
    for (InstanceAssignment assignment : store.listAssignments()) {
      if (!deploymentNames.contains(assignment.deploymentName())) {
        mutations.propose(
            new StateMutation.RemoveAssignment(
                assignment.deploymentName(), assignment.instanceIndex()));
      }
    }

    for (DeploymentSpec spec : store.listDeployments()) {
      reconcileDeployment(spec);
    }
  }

  private void reconcileDeployment(DeploymentSpec spec) {
    // The autoscaler's effective count stands in for the user-submitted replicas whenever a
    // policy is present; absent a policy (or absent any computed value yet), the submitted count
    // is exactly what's used, unchanged from before autoscaling existed.
    int replicas = store.getEffectiveReplicas(spec.name()).orElse(spec.replicas());
    List<InstanceAssignment> existing = store.listAssignmentsFor(spec.name());

    // Scale-down: an assigned index beyond the current replica count is removed immediately (a
    // desired-state edit only) -- the agent's own stop()/StopModule drain timing owns teardown,
    // not this reconciler.
    for (InstanceAssignment assignment : existing) {
      if (assignment.instanceIndex() >= replicas) {
        mutations.propose(
            new StateMutation.RemoveAssignment(spec.name(), assignment.instanceIndex()));
      }
    }

    handleRollingUpdate(spec);

    // Re-read: scale-down and/or the rolling-update step above may have just removed an entry.
    existing = store.listAssignmentsFor(spec.name());
    Set<Integer> existingIndices = new HashSet<>();
    for (InstanceAssignment assignment : existing) {
      existingIndices.add(assignment.instanceIndex());
    }

    if (missingIndices(replicas, existingIndices).isEmpty()) {
      return;
    }

    ModuleDescriptor descriptor;
    try {
      descriptor = ModuleArtifactReader.read(Path.of(spec.artifactPath())).descriptor();
    } catch (RuntimeException e) {
      log.warn(
          "deployment {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      return;
    }

    for (int index : missingIndices(replicas, existingIndices)) {
      try {
        List<NodeCandidate> candidates = buildCandidates(spec.name());
        String nodeId =
            scheduler.place(
                spec.name(),
                index,
                descriptor.isolationTier(),
                descriptor.resourceRequest(),
                spec.placement().antiAffinityAcrossNodes(),
                spec.tenantId(),
                spec.placement().requiredNodeLabels().orElse(Set.of()),
                candidates);
        mutations.propose(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    spec.name(), index, nodeId, spec.moduleId(), spec.artifactPath())));
      } catch (GimleSchedulingException e) {
        // Left unplaced; the next tick retries from the same full snapshot, no special-cased
        // retry bookkeeping needed -- this is what "level-triggered, converge from any snapshot"
        // buys: a missed placement this tick is indistinguishable from one that failed and is
        // being retried.
        log.warn("could not place {} instance {}: {}", spec.name(), index, e.getMessage());
      }
    }
  }

  /**
   * If a rollout is already in flight for this deployment, checks whether the replacement at that
   * index has both landed with the new {@code moduleId} and reported ready -- clearing {@code
   * rollingIndex} once it has, otherwise leaving everything untouched (a stalled rollout blocks
   * only itself, never other indices). Only once no rollout is in flight does it look for a new
   * mismatch to start, picking the lowest such index and removing its stale assignment so the
   * caller's ordinary missing-index placement logic re-places it with the current spec's {@code
   * moduleId} -- the exact same placement path a fresh scale-up already uses.
   */
  private void handleRollingUpdate(DeploymentSpec spec) {
    Optional<Integer> rollingIndex = store.getRollingIndex(spec.name());
    if (rollingIndex.isPresent()) {
      int index = rollingIndex.get();
      Optional<InstanceAssignment> current =
          store.listAssignmentsFor(spec.name()).stream()
              .filter(a -> a.instanceIndex() == index)
              .findFirst();
      if (current.isPresent()
          && current.get().moduleId().equals(spec.moduleId())
          && isReady(current.get())) {
        mutations.propose(new StateMutation.ClearRollingIndex(spec.name()));
      }
      // Either still waiting for the replacement to become ready, or it was already removed and
      // is awaiting re-placement below -- either way, only one index migrates at a time.
      return;
    }

    store.listAssignmentsFor(spec.name()).stream()
        // UNSPECIFIED_MODULE (the three-argument constructor's placeholder) means "don't care
        // which version this is" -- treating it as a real mismatch would spuriously trigger a
        // rollout for every assignment that simply never specified one.
        .filter(assignment -> !assignment.moduleId().equals(InstanceAssignment.UNSPECIFIED_MODULE))
        .filter(assignment -> !assignment.moduleId().equals(spec.moduleId()))
        .min(Comparator.comparingInt(InstanceAssignment::instanceIndex))
        .ifPresent(
            mismatched -> {
              mutations.propose(
                  new StateMutation.RemoveAssignment(spec.name(), mismatched.instanceIndex()));
              mutations.propose(
                  new StateMutation.PutRollingIndex(spec.name(), mismatched.instanceIndex()));
              log.info(
                  "deployment {} instance {} is on an old module version; rolling it forward",
                  spec.name(),
                  mismatched.instanceIndex());
            });
  }

  private boolean isReady(InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .anyMatch(
            obs ->
                obs.deploymentName().equals(assignment.deploymentName())
                    && obs.instanceIndex() == assignment.instanceIndex()
                    && obs.ready());
  }

  private static List<Integer> missingIndices(int replicas, Set<Integer> existingIndices) {
    List<Integer> missing = new ArrayList<>();
    for (int index = 0; index < replicas; index++) {
      if (!existingIndices.contains(index)) {
        missing.add(index);
      }
    }
    return missing;
  }

  private List<NodeCandidate> buildCandidates(String deploymentName) {
    Set<String> nodesAlreadyRunningThisDeployment = new HashSet<>();
    // Every distinct tenantId already assigned to each node, across every deployment (not just
    // this one) -- the scheduler needs the full picture to enforce node-level tenant segregation
    // for Tier 2/3 placements.
    Map<String, Set<String>> tenantsByNode = new HashMap<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      if (assignment.deploymentName().equals(deploymentName)) {
        nodesAlreadyRunningThisDeployment.add(assignment.nodeId());
      }
      store
          .getDeployment(assignment.deploymentName())
          .flatMap(DeploymentSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }

    List<NodeCandidate> candidates = new ArrayList<>();
    for (NodeRegistration registration : store.listNodeRegistrations()) {
      Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(registration.nodeId());
      if (heartbeat.isEmpty()) {
        continue; // no capacity report yet; not a placement candidate until it heartbeats
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisDeployment.contains(registration.nodeId()),
              tenantsByNode.getOrDefault(registration.nodeId(), Set.of())));
    }
    return candidates;
  }
}
