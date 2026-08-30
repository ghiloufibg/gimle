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
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every read method {@code ApiServer}, the five reconcilers, and {@code Authorizer} call outside
 * this package -- implemented by both {@link StateStore} (a reconciler/{@code Authorizer} unit
 * test's fast, network-free fixture, unchanged since before the etcd-store-extraction) and {@code
 * com.gimle.mimir.rpc.StoreClient} (what those same classes are constructed with in production).
 * Extracted once both needed to be interchangeable behind one field: without this interface,
 * "{@code StoreClient} mirrors {@code StateStore}'s method signatures" and "reconciler tests keep
 * constructing a plain {@code StateStore}" can't both be true for the same statically-typed field.
 * Deliberately excludes every write ({@code putNodeHeartbeat} included) -- those go through {@link
 * com.gimle.mimir.raft.MutationSink}/{@code StoreClient}'s own {@code putHeartbeat}, never through
 * a plain read interface.
 */
public interface StoreReader {

  List<Account> listAccounts();

  Optional<Tenant> getTenant(String id);

  Optional<DeploymentSpec> getDeployment(Optional<String> tenantId, String name);

  /**
   * The compare-and-set precondition value for {@code ApiServer}'s deployment apply/delete/rollback
   * handlers -- see {@code StateMutation.PutDeployment}/{@code RemoveDeployment}'s own javadoc. 0
   * for a deployment that has never existed or was fully removed; every successful {@code
   * putDeployment} bumps it, every {@code removeDeployment} resets it, so a fresh {@code apply}
   * under the same name starts a new generation lineage rather than continuing the old one.
   */
  long getDeploymentGeneration(Optional<String> tenantId, String name);

  List<DeploymentSpec> listDeployments();

  Optional<ServiceSpec> getService(Optional<String> tenantId, String name);

  List<ServiceSpec> listServices();

  Optional<NetworkPolicySpec> getNetworkPolicy(String tenantId, String name);

  List<NetworkPolicySpec> listNetworkPolicies();

  List<InstanceAssignment> listAssignmentsFor(Optional<String> tenantId, String deploymentName);

  boolean isQuotaViolating(Optional<String> tenantId, String deploymentName);

  boolean isNodeCordoned(String nodeId);

  /**
   * Empty means the node is open to any tenant -- see {@code StateStore#putNodeTaint}'s javadoc.
   */
  Set<String> getNodeTaints(String nodeId);

  boolean isCertificateRevoked(String serialNumber);

  Set<String> listRevokedCertificateSerials();

  Optional<WorkloadTokenRecord> getWorkloadToken(String key);

  List<InstanceAssignment> listAssignments();

  Optional<JobSpec> getJobSpec(Optional<String> tenantId, String name);

  List<JobSpec> listJobSpecs();

  List<JobRun> listJobRunsFor(Optional<String> tenantId, String jobName);

  List<JobRun> listJobRuns();

  /** Empty means "not yet terminal" -- see {@code StateStore#jobPhases}'s own field javadoc. */
  Optional<JobPhase> getJobPhase(Optional<String> tenantId, String jobName);

  /**
   * Empty until the job reaches a terminal phase -- see {@code StateStore#jobRunSummaries}'s own
   * field javadoc.
   */
  Optional<JobRunSummary> getJobRunSummary(Optional<String> tenantId, String jobName);

  Optional<CronJobSpec> getCronJobSpec(Optional<String> tenantId, String name);

  List<CronJobSpec> listCronJobSpecs();

  /** Empty means "never fired yet" -- see {@code StateStore#cronJobLastSchedule}'s own javadoc. */
  Optional<Instant> getCronJobLastSchedule(Optional<String> tenantId, String name);

  Optional<DaemonSetSpec> getDaemonSetSpec(Optional<String> tenantId, String name);

  List<DaemonSetSpec> listDaemonSetSpecs();

  List<DaemonSetAssignment> listDaemonSetAssignments();

  List<DaemonSetAssignment> listDaemonSetAssignmentsFor(
      Optional<String> tenantId, String daemonSetName);

  Set<String> getRollingDaemonSetNodes(Optional<String> tenantId, String daemonSetName);

  Optional<StatefulSetSpec> getStatefulSetSpec(Optional<String> tenantId, String name);

  List<StatefulSetSpec> listStatefulSetSpecs();

  List<StatefulSetAssignment> listStatefulSetAssignments();

  List<StatefulSetAssignment> listStatefulSetAssignmentsFor(
      Optional<String> tenantId, String statefulSetName);

  Optional<Integer> getRollingStatefulSetIndex(Optional<String> tenantId, String statefulSetName);

  Optional<String> getStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex);

  List<NodeRegistration> listNodeRegistrations();

  List<Tenant> listTenants();

  List<ConfigEntry> listConfigEntriesFor(String tenantId);

  List<Role> listRoles();

  Optional<Role> getRole(String name);

  List<RoleBinding> listRoleBindings();

  Optional<RoleBinding> getRoleBinding(String id);

  Optional<Account> getAccount(String username);

  Optional<NodeRegistration> getNodeRegistration(String nodeId);

  Optional<Integer> getEffectiveReplicas(Optional<String> tenantId, String deploymentName);

  Set<Integer> getRollingIndices(Optional<String> tenantId, String deploymentName);

  Map<Integer, Integer> getSurgeIndices(Optional<String> tenantId, String deploymentName);

  /**
   * Leader-local; a {@code StoreClient} implementation routes this through the current leader
   * specifically, unlike every other read here -- see {@code StateStore.putNodeHeartbeat}'s own
   * javadoc.
   */
  Optional<ObservedHeartbeat> getNodeHeartbeat(String nodeId);

  Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex);

  List<ReconcilerInstanceState> listReconcilerInstanceStates();

  Optional<WorkloadHealthState> getWorkloadHealthState(
      Optional<String> tenantId, String workloadKind, String workloadName, String slot);

  List<WorkloadHealthState> listWorkloadHealthStates();

  List<InstanceEvent> listInstanceEvents(
      Optional<String> tenantId, String deploymentName, int instanceIndex);

  List<AuditEvent> listAuditEvents(
      Optional<String> principal,
      Optional<String> resourceKind,
      Optional<String> tenantId,
      Optional<Long> since);

  /** Newest-first -- see {@code StateStore#listControllerRevisions}'s own javadoc. */
  List<ControllerRevision> listControllerRevisions(
      String workloadKind, Optional<String> tenantId, String name);

  Optional<ControllerRevision> getControllerRevision(
      String workloadKind, Optional<String> tenantId, String name, int revision);

  Optional<LimitRangeSpec> getLimitRange(String tenantId);

  List<LimitRangeSpec> listLimitRanges();

  boolean isLimitRangeViolating(Optional<String> tenantId, String deploymentName);

  /**
   * Empty when not violating; present with {@link LimitRangeSpec#violation}'s own text otherwise.
   */
  Optional<String> limitRangeViolationReason(Optional<String> tenantId, String deploymentName);
}
