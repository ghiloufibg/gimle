package com.gimle.controlplane.store;

import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A full, point-in-time copy of every resource kind {@link StateStore} holds except {@code
 * nodeHeartbeats} (heartbeats are never Raft-replicated, so they never enter a snapshot either) --
 * what a Raft leader sends via {@code InstallSnapshot} to a follower that has fallen behind the
 * leader's retained log.
 */
public record StateSnapshot(
    List<DeploymentSpec> deployments,
    List<InstanceAssignment> assignments,
    List<NodeRegistration> nodeRegistrations,
    Map<String, Integer> rollingIndices,
    Map<String, Integer> effectiveReplicas,
    List<Tenant> tenants,
    Set<String> quotaViolatingDeployments,
    List<ConfigEntry> configEntries) {

  public StateSnapshot {
    deployments = List.copyOf(deployments);
    assignments = List.copyOf(assignments);
    nodeRegistrations = List.copyOf(nodeRegistrations);
    rollingIndices = Map.copyOf(rollingIndices);
    effectiveReplicas = Map.copyOf(effectiveReplicas);
    tenants = List.copyOf(tenants);
    quotaViolatingDeployments = Set.copyOf(quotaViolatingDeployments);
    configEntries = List.copyOf(configEntries);
  }
}
