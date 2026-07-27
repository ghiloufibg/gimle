package com.gimle.controlplane.autoscale;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.raft.MutationSink;
import com.gimle.controlplane.raft.StateMutation;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.ObservedHeartbeat;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Horizontal autoscaling: for every deployment carrying an {@link AutoscalePolicy}, computes
 * average observed CPU utilization ({@code cpuMillicoresUsed} &divide; the module descriptor's
 * {@code resourceRequest.cpuMillicores()}) across every currently-{@code ready} instance, and
 * writes an effective replica count clamped to {@code [minReplicas, maxReplicas]}, adjusted by
 * exactly one replica per tick toward the computed ideal rather than jumping straight there --
 * avoiding thrash on a single noisy sample, the same reasoning {@code RestartTracker}'s backoff
 * already applies to a different oscillation risk. {@link
 * com.gimle.controlplane.reconcile.DeploymentReconciler} reads this effective count in place of the
 * user-submitted {@code replicas} whenever a policy is present; this reconciler never touches
 * {@link com.gimle.controlplane.store.InstanceAssignment}s itself.
 */
public final class AutoscaleReconciler {

  private static final Logger log = LoggerFactory.getLogger(AutoscaleReconciler.class);

  private final StateStore store;
  private final MutationSink mutations;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public AutoscaleReconciler(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  public AutoscaleReconciler(StateStore store, MutationSink mutations) {
    this.store = store;
    this.mutations = mutations;
  }

  public void reconcileOnce() {
    for (DeploymentSpec spec : store.listDeployments()) {
      spec.autoscale().ifPresent(policy -> reconcileDeployment(spec, policy));
    }
  }

  private void reconcileDeployment(DeploymentSpec spec, AutoscalePolicy policy) {
    int currentEffective = store.getEffectiveReplicas(spec.name()).orElse(spec.replicas());

    ModuleDescriptor descriptor;
    try {
      descriptor = ModuleArtifactReader.read(Path.of(spec.artifactPath())).descriptor();
    } catch (RuntimeException e) {
      log.warn(
          "deployment {} references an unreadable artifact {}; leaving its effective replica"
              + " count unchanged: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      putEffectiveReplicas(spec.name(), clamp(currentEffective, policy));
      return;
    }
    long cpuRequestMillicores = descriptor.resourceRequest().cpuMillicores();
    if (cpuRequestMillicores <= 0) {
      putEffectiveReplicas(spec.name(), clamp(currentEffective, policy));
      return;
    }

    List<InstanceObservation> readyObservations = readyInstanceObservations(spec.name());
    if (readyObservations.isEmpty()) {
      // No signal yet (nothing ready/reporting): hold the current count rather than guessing.
      putEffectiveReplicas(spec.name(), clamp(currentEffective, policy));
      return;
    }

    double averageUtilizationPercent =
        readyObservations.stream()
            .mapToDouble(obs -> (obs.cpuMillicoresUsed() * 100.0) / cpuRequestMillicores)
            .average()
            .orElse(0.0);

    int idealReplicas =
        (int)
            Math.ceil(
                currentEffective
                    * (averageUtilizationPercent / policy.targetCpuUtilizationPercent()));
    int clampedIdeal = clamp(idealReplicas, policy);

    int nextEffective = currentEffective;
    if (clampedIdeal > currentEffective) {
      nextEffective = currentEffective + 1;
    } else if (clampedIdeal < currentEffective) {
      nextEffective = currentEffective - 1;
    }
    nextEffective = clamp(nextEffective, policy);

    if (nextEffective != currentEffective) {
      log.info(
          "deployment {}: average CPU utilization {}%, target {}%; adjusting effective replicas"
              + " {} -> {}",
          spec.name(),
          averageUtilizationPercent,
          policy.targetCpuUtilizationPercent(),
          currentEffective,
          nextEffective);
    }
    putEffectiveReplicas(spec.name(), nextEffective);
  }

  private void putEffectiveReplicas(String deploymentName, int replicas) {
    mutations.propose(new StateMutation.PutEffectiveReplicas(deploymentName, replicas));
  }

  private static int clamp(int value, AutoscalePolicy policy) {
    return Math.max(policy.minReplicas(), Math.min(policy.maxReplicas(), value));
  }

  private List<InstanceObservation> readyInstanceObservations(String deploymentName) {
    List<InstanceObservation> result = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(deploymentName)) {
      store
          .getNodeHeartbeat(assignment.nodeId())
          .map(ObservedHeartbeat::heartbeat)
          .map(NodeHeartbeat::instances)
          .orElse(List.of())
          .stream()
          .filter(
              obs ->
                  obs.deploymentName().equals(deploymentName)
                      && obs.instanceIndex() == assignment.instanceIndex()
                      && obs.ready())
          .findFirst()
          .ifPresent(result::add);
    }
    return result;
  }
}
