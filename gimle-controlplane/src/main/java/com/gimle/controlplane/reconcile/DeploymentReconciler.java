package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.ObservedHeartbeat;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures an {@link InstanceAssignment} exists for every index {@code 0..replicas-1} of every
 * {@link DeploymentSpec} (design §7). Level-triggered: every tick re-derives the full set of
 * assignments a from-scratch run would produce from the current snapshot, rather than reacting to
 * what changed since last tick -- deleting a deployment, scaling it, or a fresh empty store all
 * converge through the exact same code path.
 */
public final class DeploymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(DeploymentReconciler.class);

  private final StateStore store;
  private final Scheduler scheduler;

  public DeploymentReconciler(StateStore store, Scheduler scheduler) {
    this.store = store;
    this.scheduler = scheduler;
  }

  public void reconcile_once() {
    Set<String> deploymentNames = new HashSet<>();
    for (DeploymentSpec spec : store.list_deployments()) {
      deploymentNames.add(spec.name());
    }

    // A deployment no longer in desired state: every one of its assignments is stale.
    for (InstanceAssignment assignment : store.list_assignments()) {
      if (!deploymentNames.contains(assignment.deploymentName())) {
        store.remove_assignment(assignment.deploymentName(), assignment.instanceIndex());
      }
    }

    for (DeploymentSpec spec : store.list_deployments()) {
      reconcile_deployment(spec);
    }
  }

  private void reconcile_deployment(DeploymentSpec spec) {
    List<InstanceAssignment> existing = store.list_assignments_for(spec.name());

    // Scale-down: an assigned index beyond the current replica count is removed immediately (a
    // desired-state edit only) -- the agent's own stop()/StopModule drain timing owns teardown,
    // not this reconciler (design §11.4).
    Set<Integer> existingIndices = new HashSet<>();
    for (InstanceAssignment assignment : existing) {
      existingIndices.add(assignment.instanceIndex());
      if (assignment.instanceIndex() >= spec.replicas()) {
        store.remove_assignment(spec.name(), assignment.instanceIndex());
      }
    }

    if (missing_indices(spec, existingIndices).isEmpty()) {
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

    for (int index : missing_indices(spec, existingIndices)) {
      try {
        List<NodeCandidate> candidates = build_candidates(spec.name());
        String nodeId =
            scheduler.place(
                spec.name(),
                index,
                descriptor.isolationTier(),
                descriptor.resourceRequest(),
                spec.placement().antiAffinityAcrossNodes(),
                candidates);
        store.put_assignment(new InstanceAssignment(spec.name(), index, nodeId));
      } catch (GimleSchedulingException e) {
        // Left unplaced; the next tick retries from the same full snapshot, no special-cased
        // retry bookkeeping needed -- this is what "level-triggered, converge from any snapshot"
        // buys: a missed placement this tick is indistinguishable from one that failed and is
        // being retried.
        log.warn("could not place {} instance {}: {}", spec.name(), index, e.getMessage());
      }
    }
  }

  private static List<Integer> missing_indices(DeploymentSpec spec, Set<Integer> existingIndices) {
    List<Integer> missing = new ArrayList<>();
    for (int index = 0; index < spec.replicas(); index++) {
      if (!existingIndices.contains(index)) {
        missing.add(index);
      }
    }
    return missing;
  }

  private List<NodeCandidate> build_candidates(String deploymentName) {
    Set<String> nodesAlreadyRunningThisDeployment = new HashSet<>();
    for (InstanceAssignment assignment : store.list_assignments_for(deploymentName)) {
      nodesAlreadyRunningThisDeployment.add(assignment.nodeId());
    }

    List<NodeCandidate> candidates = new ArrayList<>();
    for (NodeRegistration registration : store.list_node_registrations()) {
      Optional<ObservedHeartbeat> heartbeat = store.get_node_heartbeat(registration.nodeId());
      if (heartbeat.isEmpty()) {
        continue; // no capacity report yet; not a placement candidate until it heartbeats
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisDeployment.contains(registration.nodeId())));
    }
    return candidates;
  }
}
