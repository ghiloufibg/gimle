package com.gimle.mimir.store;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import java.time.Instant;
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
    List<JobSpec> jobSpecs,
    List<JobRun> jobRuns,
    Map<String, JobPhase> jobPhases,
    List<CronJobSpec> cronJobSpecs,
    Map<String, Instant> cronJobLastSchedule,
    List<NodeRegistration> nodeRegistrations,
    Map<String, Integer> rollingIndices,
    Map<String, Integer> effectiveReplicas,
    List<Tenant> tenants,
    Set<String> quotaViolatingDeployments,
    List<ConfigEntry> configEntries,
    List<Role> roles,
    List<RoleBinding> roleBindings,
    List<Account> accounts,
    List<ReconcilerInstanceState> reconcilerInstanceStates,
    Set<String> cordonedNodes,
    List<InstanceEvent> instanceEvents,
    List<AuditEvent> auditEvents) {

  public StateSnapshot {
    deployments = List.copyOf(deployments);
    assignments = List.copyOf(assignments);
    jobSpecs = List.copyOf(jobSpecs);
    jobRuns = List.copyOf(jobRuns);
    jobPhases = Map.copyOf(jobPhases);
    cronJobSpecs = List.copyOf(cronJobSpecs);
    cronJobLastSchedule = Map.copyOf(cronJobLastSchedule);
    nodeRegistrations = List.copyOf(nodeRegistrations);
    rollingIndices = Map.copyOf(rollingIndices);
    effectiveReplicas = Map.copyOf(effectiveReplicas);
    tenants = List.copyOf(tenants);
    quotaViolatingDeployments = Set.copyOf(quotaViolatingDeployments);
    configEntries = List.copyOf(configEntries);
    roles = List.copyOf(roles);
    roleBindings = List.copyOf(roleBindings);
    accounts = List.copyOf(accounts);
    reconcilerInstanceStates = List.copyOf(reconcilerInstanceStates);
    cordonedNodes = Set.copyOf(cordonedNodes);
    instanceEvents = List.copyOf(instanceEvents);
    auditEvents = List.copyOf(auditEvents);
  }
}
