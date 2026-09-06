package com.gimle.mimir.store;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.IngressSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A full, point-in-time copy of every resource kind {@link StateStore} holds except {@code
 * nodeHeartbeats} (heartbeats are never Raft-replicated, so they never enter a snapshot either) --
 * what a Raft leader sends via {@code InstallSnapshot} to a follower that has fallen behind the
 * leader's retained log.
 */
public record StateSnapshot(
    List<DeploymentSpec> deployments,
    Map<String, Long> deploymentGenerations,
    List<InstanceAssignment> assignments,
    List<JobSpec> jobSpecs,
    List<JobRun> jobRuns,
    Map<String, JobPhase> jobPhases,
    List<JobRunSummary> jobRunSummaries,
    List<CronJobSpec> cronJobSpecs,
    Map<String, Instant> cronJobLastSchedule,
    List<DaemonSetSpec> daemonSetSpecs,
    List<DaemonSetAssignment> daemonSetAssignments,
    Map<String, Set<String>> rollingDaemonSetNodes,
    List<StatefulSetSpec> statefulSetSpecs,
    List<StatefulSetAssignment> statefulSetAssignments,
    Map<String, Set<Integer>> rollingStatefulSetIndices,
    Map<String, String> statefulSetIndexNodes,
    List<NodeRegistration> nodeRegistrations,
    Map<String, Set<Integer>> rollingIndices,
    Map<String, Map<Integer, Integer>> surgeIndices,
    Map<String, Integer> effectiveReplicas,
    List<Tenant> tenants,
    Set<String> quotaViolatingDeployments,
    List<ConfigEntry> configEntries,
    List<Role> roles,
    List<RoleBinding> roleBindings,
    List<Account> accounts,
    List<ReconcilerInstanceState> reconcilerInstanceStates,
    Set<String> cordonedNodes,
    // Keyed by the store's own tenant-scoped instance-events key (see StateStore#instanceEventsKey)
    // rather than a flat List<InstanceEvent> -- InstanceEvent itself carries no tenantId (see
    // StateStore#putInstanceEvent's own javadoc), so the tenant each list belongs to would
    // otherwise be lost the moment every instance's events were flattened into one list.
    Map<String, List<InstanceEvent>> instanceEvents,
    List<AuditEvent> auditEvents,
    List<ServiceSpec> services,
    List<NetworkPolicySpec> networkPolicies,
    List<ControllerRevision> controllerRevisions,
    List<LimitRangeSpec> limitRanges,
    Map<String, String> limitRangeViolations,
    Set<String> revokedCertificateSerials,
    List<WorkloadTokenRecord> workloadTokens,
    // Receipts for completed writes that carried a caller-supplied request id -- snapshotted
    // like every other replicated map, since a receipt lost to a leader change or a log
    // compaction would silently turn a retry back into a second execution of the write.
    List<RequestOutcomeRecord> requestOutcomes,
    Map<String, Set<String>> nodeTaints,
    List<KindDefinitionSpec> kindDefinitions,
    List<CustomResource> customResources,
    List<WorkloadHealthState> workloadHealthStates,
    // Console session revocation: username -> the epoch-milli watermark set by that user's last
    // logout. A session token issued at or before its own username's watermark is rejected even
    // though its HMAC signature still verifies -- see StateStore#putSessionRevocation's javadoc.
    Map<String, Long> sessionRevokedBeforeEpochMilli,
    List<AlertRuleSpec> alertRules,
    // When the autoscaler last moved each deployment's effectiveReplicas -- what makes an
    // AutoscalePolicy's stabilization windows survive a control-plane restart or failover.
    Map<String, Instant> deploymentLastScale,
    List<IngressSpec> ingresses,
    // The eligible-node count DaemonSetReconciler last computed for each daemonset -- see
    // StateStore#daemonSetDesiredCounts's own field javadoc.
    Map<String, Integer> daemonSetDesiredCounts,
    // Durable alert firing verdicts, keyed the same tenant-scoped way alertRules itself is -- see
    // StateStore#putAlertFiringState's own javadoc for the absent/true/false three-state meaning.
    Map<String, Boolean> alertFiringState,
    Set<Byte> retiredSecretsKeyIds) {

  public StateSnapshot {
    deployments = List.copyOf(deployments);
    ingresses = List.copyOf(ingresses);
    deploymentGenerations = Map.copyOf(deploymentGenerations);
    assignments = List.copyOf(assignments);
    jobSpecs = List.copyOf(jobSpecs);
    jobRuns = List.copyOf(jobRuns);
    jobPhases = Map.copyOf(jobPhases);
    jobRunSummaries = List.copyOf(jobRunSummaries);
    cronJobSpecs = List.copyOf(cronJobSpecs);
    cronJobLastSchedule = Map.copyOf(cronJobLastSchedule);
    daemonSetSpecs = List.copyOf(daemonSetSpecs);
    daemonSetAssignments = List.copyOf(daemonSetAssignments);
    // Map.copyOf alone only makes the outer map immutable, not each entry's own Set value --
    // deep-copied here so a caller mutating a Set it passed in after construction can't reach back
    // into this supposedly-immutable snapshot.
    rollingDaemonSetNodes =
        rollingDaemonSetNodes.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    statefulSetSpecs = List.copyOf(statefulSetSpecs);
    statefulSetAssignments = List.copyOf(statefulSetAssignments);
    // Map.copyOf alone only makes the outer map immutable, not each entry's own Set value -- same
    // deep-copy reasoning as rollingDaemonSetNodes/rollingIndices above.
    rollingStatefulSetIndices =
        rollingStatefulSetIndices.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    statefulSetIndexNodes = Map.copyOf(statefulSetIndexNodes);
    nodeRegistrations = List.copyOf(nodeRegistrations);
    rollingIndices =
        rollingIndices.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    // Map.copyOf alone only makes the outer map immutable, not each entry's own Map value -- same
    // deep-copy reasoning as rollingDaemonSetNodes/rollingIndices above.
    surgeIndices =
        surgeIndices.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
    effectiveReplicas = Map.copyOf(effectiveReplicas);
    tenants = List.copyOf(tenants);
    quotaViolatingDeployments = Set.copyOf(quotaViolatingDeployments);
    configEntries = List.copyOf(configEntries);
    roles = List.copyOf(roles);
    roleBindings = List.copyOf(roleBindings);
    accounts = List.copyOf(accounts);
    reconcilerInstanceStates = List.copyOf(reconcilerInstanceStates);
    cordonedNodes = Set.copyOf(cordonedNodes);
    // Deep-copied for the same reason rollingDaemonSetNodes/rollingIndices are above.
    instanceEvents =
        instanceEvents.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    auditEvents = List.copyOf(auditEvents);
    services = List.copyOf(services);
    networkPolicies = List.copyOf(networkPolicies);
    controllerRevisions = List.copyOf(controllerRevisions);
    limitRanges = List.copyOf(limitRanges);
    limitRangeViolations = Map.copyOf(limitRangeViolations);
    revokedCertificateSerials = Set.copyOf(revokedCertificateSerials);
    workloadTokens = List.copyOf(workloadTokens);
    requestOutcomes = List.copyOf(requestOutcomes);
    // Deep-copied for the same reason rollingDaemonSetNodes/rollingIndices are above.
    nodeTaints =
        nodeTaints.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    kindDefinitions = List.copyOf(kindDefinitions);
    customResources = List.copyOf(customResources);
    workloadHealthStates = List.copyOf(workloadHealthStates);
    sessionRevokedBeforeEpochMilli = Map.copyOf(sessionRevokedBeforeEpochMilli);
    alertRules = List.copyOf(alertRules);
    deploymentLastScale = Map.copyOf(deploymentLastScale);
    daemonSetDesiredCounts = Map.copyOf(daemonSetDesiredCounts);
    alertFiringState = Map.copyOf(alertFiringState);
    retiredSecretsKeyIds = Set.copyOf(retiredSecretsKeyIds);
  }
}
