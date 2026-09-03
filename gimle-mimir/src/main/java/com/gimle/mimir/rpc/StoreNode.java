package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.exception.GimleRaftException;
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
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.RaftCodec;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.WorkloadHealthState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server side of {@link StoreRpc}: wraps an already-constructed {@link RaftNode} + {@link
 * StateStore}, dispatching every request either straight to a {@code StateStore} getter (any node
 * may answer) or, for {@link StoreRpc.Propose}/{@link StoreRpc.PutHeartbeat}/{@link
 * StoreRpc.AcquireOrRenewLease}/{@link StoreRpc.ReleaseLease}/{@link StoreRpc.AddServer}/{@link
 * StoreRpc.RemoveServer}/{@link StoreRpc.GetNodeHeartbeat}/{@link
 * StoreRpc.ListConfigEntriesForLinearizable}, through a leader check first, translating a
 * non-leader into {@link StoreRpc.NotLeader} carrying the leader's *client* address rather than its
 * Raft ID -- resolved via {@code raftIdToClientAddress}. {@link StoreRpc.GetNodeHeartbeat} and
 * {@link StoreRpc.ListConfigEntriesForLinearizable} are the two *reads* in this leader-only group,
 * for two different reasons -- see each type's own javadoc. Unlike every other field here, {@code
 * raftIdToClientAddress} is a *live* reference the caller ({@code StoreMain}) keeps mutating as
 * membership changes -- {@code StoreNode} takes no defensive copy of it on purpose, so a peer added
 * after this node was constructed still resolves correctly.
 */
public final class StoreNode implements StoreRpcHandler {

  private final RaftNode raftNode;
  private final StateStore store;
  private final Map<String, String> raftIdToClientAddress;

  public StoreNode(RaftNode raftNode, StateStore store, Map<String, String> raftIdToClientAddress) {
    this.raftNode = raftNode;
    this.store = store;
    this.raftIdToClientAddress = raftIdToClientAddress;
  }

  @Override
  public StoreRpc.Response handle(StoreRpc.Request request) {
    return switch (request) {
      case StoreRpc.Propose r -> handlePropose(r);
      case StoreRpc.PutHeartbeat r -> handlePutHeartbeat(r);
      case StoreRpc.AcquireOrRenewLease r -> handleAcquireOrRenewLease(r);
      case StoreRpc.ReleaseLease r -> handleReleaseLease(r);
      case StoreRpc.AddServer r -> handleAddServer(r);
      case StoreRpc.RemoveServer r -> handleRemoveServer(r);
      case StoreRpc.ListAccounts r -> new StoreRpc.AccountListResult(store.listAccounts());
      case StoreRpc.GetTenant r -> tenantResult(store.getTenant(r.id()));
      case StoreRpc.GetDeployment r ->
          deploymentResult(store.getDeployment(r.tenantId(), r.name()));
      case StoreRpc.GetDeploymentGeneration r ->
          new StoreRpc.GenerationResult(store.getDeploymentGeneration(r.tenantId(), r.name()));
      case StoreRpc.ListDeployments r -> new StoreRpc.DeploymentListResult(store.listDeployments());
      case StoreRpc.GetService r -> serviceResult(store.getService(r.tenantId(), r.name()));
      case StoreRpc.ListServices r -> new StoreRpc.ServiceListResult(store.listServices());
      case StoreRpc.GetNetworkPolicy r ->
          networkPolicyResult(store.getNetworkPolicy(r.tenantId(), r.name()));
      case StoreRpc.ListNetworkPolicies r ->
          new StoreRpc.NetworkPolicyListResult(store.listNetworkPolicies());
      case StoreRpc.GetIngress r -> ingressResult(store.getIngress(r.tenantId(), r.name()));
      case StoreRpc.ListIngresses r -> new StoreRpc.IngressListResult(store.listIngresses());
      case StoreRpc.GetAlertRule r -> alertRuleResult(store.getAlertRule(r.tenantId(), r.name()));
      case StoreRpc.ListAlertRules r -> new StoreRpc.AlertRuleListResult(store.listAlertRules());
      case StoreRpc.GetLimitRange r -> limitRangeResult(store.getLimitRange(r.tenantId()));
      case StoreRpc.ListLimitRanges r -> new StoreRpc.LimitRangeListResult(store.listLimitRanges());
      case StoreRpc.ListAssignmentsFor r ->
          new StoreRpc.AssignmentListResult(
              store.listAssignmentsFor(r.tenantId(), r.deploymentName()));
      case StoreRpc.IsQuotaViolating r ->
          new StoreRpc.BoolResult(store.isQuotaViolating(r.tenantId(), r.deploymentName()));
      case StoreRpc.IsLimitRangeViolating r ->
          new StoreRpc.BoolResult(store.isLimitRangeViolating(r.tenantId(), r.deploymentName()));
      case StoreRpc.GetLimitRangeViolationReason r ->
          stringResult(store.limitRangeViolationReason(r.tenantId(), r.deploymentName()));
      case StoreRpc.ListKindDefinitions r ->
          new StoreRpc.KindDefinitionListResult(store.listKindDefinitions());
      case StoreRpc.GetKindDefinition r ->
          kindDefinitionResult(store.getKindDefinition(r.kindName()));
      case StoreRpc.ListCustomResources r ->
          new StoreRpc.CustomResourceListResult(store.listCustomResources(r.kindName()));
      case StoreRpc.ListCustomResourcesFor r ->
          new StoreRpc.CustomResourceListResult(
              store.listCustomResourcesFor(r.kindName(), r.tenantId()));
      case StoreRpc.GetCustomResource r ->
          customResourceResult(store.getCustomResource(r.kindName(), r.tenantId(), r.name()));
      case StoreRpc.IsNodeCordoned r -> new StoreRpc.BoolResult(store.isNodeCordoned(r.nodeId()));
      case StoreRpc.GetNodeTaints r ->
          new StoreRpc.StringSetResult(List.copyOf(store.getNodeTaints(r.nodeId())));
      case StoreRpc.IsCertificateRevoked r ->
          new StoreRpc.BoolResult(store.isCertificateRevoked(r.serialNumber()));
      case StoreRpc.ListRevokedCertificateSerials r ->
          new StoreRpc.StringSetResult(
              store.listRevokedCertificateSerials().stream().sorted().toList());
      case StoreRpc.GetSessionRevokedBeforeEpochMilli r ->
          new StoreRpc.GenerationResult(store.getSessionRevokedBeforeEpochMilli(r.username()));
      case StoreRpc.GetWorkloadToken r -> {
        var record = store.getWorkloadToken(r.key());
        yield new StoreRpc.WorkloadTokenResult(record.isPresent(), record.orElse(null));
      }
      case StoreRpc.ListAssignments r -> new StoreRpc.AssignmentListResult(store.listAssignments());
      case StoreRpc.GetJobSpec r -> jobSpecResult(store.getJobSpec(r.tenantId(), r.name()));
      case StoreRpc.ListJobSpecs r -> new StoreRpc.JobSpecListResult(store.listJobSpecs());
      case StoreRpc.ListJobRunsFor r ->
          new StoreRpc.JobRunListResult(store.listJobRunsFor(r.tenantId(), r.jobName()));
      case StoreRpc.ListJobRuns r -> new StoreRpc.JobRunListResult(store.listJobRuns());
      case StoreRpc.GetJobPhase r -> jobPhaseResult(store.getJobPhase(r.tenantId(), r.jobName()));
      case StoreRpc.GetJobRunSummary r ->
          jobRunSummaryResult(store.getJobRunSummary(r.tenantId(), r.jobName()));
      case StoreRpc.GetCronJobSpec r ->
          cronJobSpecResult(store.getCronJobSpec(r.tenantId(), r.name()));
      case StoreRpc.ListCronJobSpecs r ->
          new StoreRpc.CronJobSpecListResult(store.listCronJobSpecs());
      case StoreRpc.GetCronJobLastSchedule r ->
          instantResult(store.getCronJobLastSchedule(r.tenantId(), r.name()));
      case StoreRpc.GetDaemonSetSpec r ->
          daemonSetSpecResult(store.getDaemonSetSpec(r.tenantId(), r.name()));
      case StoreRpc.ListDaemonSetSpecs r ->
          new StoreRpc.DaemonSetSpecListResult(store.listDaemonSetSpecs());
      case StoreRpc.ListDaemonSetAssignments r ->
          new StoreRpc.DaemonSetAssignmentListResult(store.listDaemonSetAssignments());
      case StoreRpc.ListDaemonSetAssignmentsFor r ->
          new StoreRpc.DaemonSetAssignmentListResult(
              store.listDaemonSetAssignmentsFor(r.tenantId(), r.daemonSetName()));
      case StoreRpc.ListRollingDaemonSetNodes r ->
          new StoreRpc.StringSetResult(
              List.copyOf(store.getRollingDaemonSetNodes(r.tenantId(), r.daemonSetName())));
      case StoreRpc.GetDaemonSetDesiredCount r ->
          intResult(store.getDaemonSetDesiredCount(r.tenantId(), r.daemonSetName()));
      case StoreRpc.GetStatefulSetSpec r ->
          statefulSetSpecResult(store.getStatefulSetSpec(r.tenantId(), r.name()));
      case StoreRpc.ListStatefulSetSpecs r ->
          new StoreRpc.StatefulSetSpecListResult(store.listStatefulSetSpecs());
      case StoreRpc.ListStatefulSetAssignments r ->
          new StoreRpc.StatefulSetAssignmentListResult(store.listStatefulSetAssignments());
      case StoreRpc.ListStatefulSetAssignmentsFor r ->
          new StoreRpc.StatefulSetAssignmentListResult(
              store.listStatefulSetAssignmentsFor(r.tenantId(), r.statefulSetName()));
      case StoreRpc.GetRollingStatefulSetIndex r ->
          intResult(store.getRollingStatefulSetIndex(r.tenantId(), r.statefulSetName()));
      case StoreRpc.GetStatefulSetIndexNode r ->
          stringResult(
              store.getStatefulSetIndexNode(r.tenantId(), r.statefulSetName(), r.instanceIndex()));
      case StoreRpc.ListNodeRegistrations r ->
          new StoreRpc.NodeRegistrationListResult(store.listNodeRegistrations());
      case StoreRpc.ListTenants r -> new StoreRpc.TenantListResult(store.listTenants());
      case StoreRpc.ListConfigEntriesFor r ->
          new StoreRpc.ConfigEntryListResult(store.listConfigEntriesFor(r.tenantId()));
      case StoreRpc.ListRoles r -> new StoreRpc.RoleListResult(store.listRoles());
      case StoreRpc.GetRole r -> roleResult(store.getRole(r.name()));
      case StoreRpc.ListRoleBindings r ->
          new StoreRpc.RoleBindingListResult(store.listRoleBindings());
      case StoreRpc.GetRoleBinding r -> roleBindingResult(store.getRoleBinding(r.id()));
      case StoreRpc.GetAccount r -> accountResult(store.getAccount(r.username()));
      case StoreRpc.GetNodeRegistration r ->
          nodeRegistrationResult(store.getNodeRegistration(r.nodeId()));
      case StoreRpc.GetEffectiveReplicas r ->
          intResult(store.getEffectiveReplicas(r.tenantId(), r.deploymentName()));
      case StoreRpc.GetDeploymentLastScale r ->
          instantResult(store.getDeploymentLastScale(r.tenantId(), r.deploymentName()));
      case StoreRpc.ListRollingIndices r ->
          new StoreRpc.IntSetResult(
              List.copyOf(store.getRollingIndices(r.tenantId(), r.deploymentName())));
      case StoreRpc.ListSurgeIndices r ->
          surgeIndicesResult(store.getSurgeIndices(r.tenantId(), r.deploymentName()));
      case StoreRpc.GetNodeHeartbeat r -> handleGetNodeHeartbeat(r);
      case StoreRpc.GetSnapshot r -> handleGetSnapshot();
      case StoreRpc.ListConfigEntriesForLinearizable r -> handleListConfigEntriesForLinearizable(r);
      case StoreRpc.GetReconcilerInstanceState r ->
          reconcilerInstanceStateResult(
              store.getReconcilerInstanceState(
                  r.tenantId(), r.deploymentName(), r.instanceIndex()));
      case StoreRpc.ListReconcilerInstanceStates r ->
          new StoreRpc.ReconcilerInstanceStateListResult(store.listReconcilerInstanceStates());
      case StoreRpc.GetWorkloadHealthState r ->
          workloadHealthStateResult(
              store.getWorkloadHealthState(
                  r.tenantId(), r.workloadKind(), r.workloadName(), r.slot()));
      case StoreRpc.ListWorkloadHealthStates r ->
          new StoreRpc.WorkloadHealthStateListResult(store.listWorkloadHealthStates());
      case StoreRpc.ListInstanceEvents r ->
          new StoreRpc.InstanceEventListResult(
              store.listInstanceEvents(r.tenantId(), r.deploymentName(), r.instanceIndex()));
      case StoreRpc.ListAuditEvents r ->
          new StoreRpc.AuditEventListResult(
              store.listAuditEvents(r.principal(), r.resourceKind(), r.tenantId(), r.since()));
      case StoreRpc.GetAuditTrailStatus r ->
          new StoreRpc.AuditTrailStatusResult(store.auditTrailStatus());
      case StoreRpc.ListControllerRevisions r ->
          new StoreRpc.ControllerRevisionListResult(
              store.listControllerRevisions(r.workloadKind(), r.tenantId(), r.name()));
      case StoreRpc.GetControllerRevision r ->
          controllerRevisionResult(
              store.getControllerRevision(r.workloadKind(), r.tenantId(), r.name(), r.revision()));
      case StoreRpc.Status r ->
          new StoreRpc.StatusResult(
              raftNode.selfId(),
              raftNode.isLeader(),
              raftNode.leaderHint().orElse(""),
              raftNode.memberIds());
    };
  }

  private StoreRpc.Response handlePropose(StoreRpc.Propose request) {
    try {
      MutationOutcome outcome = raftNode.propose(request.mutation());
      if (outcome instanceof MutationOutcome.Rejected rejected) {
        // Deliberately not notLeaderResponse(): retrying this exact mutation against the correct
        // leader would reject identically (the precondition it evaluated didn't hold, computed
        // deterministically from the same replicated state every node has) -- unlike a genuine
        // not-leader redirect, which retrying elsewhere actually resolves.
        return new StoreRpc.MutationRejected(rejected.reason());
      }
      return new StoreRpc.Ok();
    } catch (GimleRaftException e) {
      return notLeaderResponse();
    }
  }

  private StoreRpc.Response handlePutHeartbeat(StoreRpc.PutHeartbeat request) {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    store.putNodeHeartbeat(request.heartbeat());
    return new StoreRpc.Ok();
  }

  private StoreRpc.Response handleAcquireOrRenewLease(StoreRpc.AcquireOrRenewLease request) {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    LeaseGrant grant =
        store.tryAcquireOrRenewLease(
            request.name(), request.holderId(), Duration.ofMillis(request.ttlMillis()));
    return new StoreRpc.LeaseResult(
        grant.granted(), grant.holderId(), grant.expiresAt().toEpochMilli());
  }

  private StoreRpc.Response handleReleaseLease(StoreRpc.ReleaseLease request) {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    store.releaseLease(request.name(), request.holderId());
    return new StoreRpc.Ok();
  }

  /**
   * Leader-only, per this class's own javadoc above: a follower's local heartbeat map is never
   * anything but empty, so answering from it (as every other read here does) would be silently
   * wrong rather than merely stale.
   */
  private StoreRpc.Response handleGetNodeHeartbeat(StoreRpc.GetNodeHeartbeat request) {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    return heartbeatResult(store.getNodeHeartbeat(request.nodeId()));
  }

  /**
   * Leader-only per {@link StoreRpc.GetSnapshot}'s own javadoc: a point-in-time backup should never
   * be a not-yet-caught-up follower's stale replay of the log.
   */
  private StoreRpc.Response handleGetSnapshot() {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    return new StoreRpc.SnapshotResult(RaftCodec.encodeSnapshot(store.snapshot()));
  }

  /**
   * Leader-only for a different reason than {@link #handleGetNodeHeartbeat}: this data is fully
   * replicated, so a follower's answer is never wrong, only possibly not-yet-caught-up with a write
   * the calling client just made through this same connection. Answering only from the leader --
   * which has already applied its own just-committed writes to its own local state by the time it
   * responds to them -- is what gives a caller like {@code SecretStore.put} a read-your-own-write
   * guarantee {@link StoreRpc.ListConfigEntriesFor}'s round-robin routing cannot.
   */
  private StoreRpc.Response handleListConfigEntriesForLinearizable(
      StoreRpc.ListConfigEntriesForLinearizable request) {
    if (!raftNode.isLeader()) {
      return notLeaderResponse();
    }
    return new StoreRpc.ConfigEntryListResult(store.listConfigEntriesFor(request.tenantId()));
  }

  /**
   * Maps every rejection reason ({@link GimleRaftException#alreadyAMember}, {@link
   * GimleRaftException#membershipChangeInFlight}, a genuine non-leader, or a proposal that timed
   * out) onto the same {@link StoreRpc.NotLeader} redirect-and-retry response {@link
   * #handlePropose} already uses for every {@code StateMutation} rejection -- deliberately not a
   * dedicated response per rejection reason, matching that existing precedent rather than inventing
   * a new one here.
   */
  private StoreRpc.Response handleAddServer(StoreRpc.AddServer request) {
    try {
      raftNode.addServer(
          request.peerId(),
          new PeerAddress(request.host(), request.raftPort(), request.clientPort()));
      return new StoreRpc.Ok();
    } catch (GimleRaftException e) {
      return notLeaderResponse();
    }
  }

  /** The symmetric removal counterpart to {@link #handleAddServer}, same rejection mapping. */
  private StoreRpc.Response handleRemoveServer(StoreRpc.RemoveServer request) {
    try {
      raftNode.removeServer(request.peerId());
      return new StoreRpc.Ok();
    } catch (GimleRaftException e) {
      return notLeaderResponse();
    }
  }

  private StoreRpc.NotLeader notLeaderResponse() {
    Optional<String> leaderClientAddress = raftNode.leaderHint().map(raftIdToClientAddress::get);
    return new StoreRpc.NotLeader(leaderClientAddress.orElse(""));
  }

  private static StoreRpc.TenantResult tenantResult(Optional<Tenant> value) {
    return value
        .map(v -> new StoreRpc.TenantResult(true, v))
        .orElseGet(() -> new StoreRpc.TenantResult(false, null));
  }

  private static StoreRpc.DeploymentResult deploymentResult(Optional<DeploymentSpec> value) {
    return value
        .map(v -> new StoreRpc.DeploymentResult(true, v))
        .orElseGet(() -> new StoreRpc.DeploymentResult(false, null));
  }

  private static StoreRpc.ServiceResult serviceResult(Optional<ServiceSpec> value) {
    return value
        .map(v -> new StoreRpc.ServiceResult(true, v))
        .orElseGet(() -> new StoreRpc.ServiceResult(false, null));
  }

  private static StoreRpc.IngressResult ingressResult(Optional<IngressSpec> value) {
    return new StoreRpc.IngressResult(value.isPresent(), value.orElse(null));
  }

  private static StoreRpc.NetworkPolicyResult networkPolicyResult(
      Optional<NetworkPolicySpec> value) {
    return value
        .map(v -> new StoreRpc.NetworkPolicyResult(true, v))
        .orElseGet(() -> new StoreRpc.NetworkPolicyResult(false, null));
  }

  private static StoreRpc.AlertRuleResult alertRuleResult(Optional<AlertRuleSpec> value) {
    return value
        .map(v -> new StoreRpc.AlertRuleResult(true, v))
        .orElseGet(() -> new StoreRpc.AlertRuleResult(false, null));
  }

  private static StoreRpc.LimitRangeResult limitRangeResult(Optional<LimitRangeSpec> value) {
    return value
        .map(v -> new StoreRpc.LimitRangeResult(true, v))
        .orElseGet(() -> new StoreRpc.LimitRangeResult(false, null));
  }

  private static StoreRpc.KindDefinitionResult kindDefinitionResult(
      Optional<KindDefinitionSpec> value) {
    return value
        .map(v -> new StoreRpc.KindDefinitionResult(true, v))
        .orElseGet(() -> new StoreRpc.KindDefinitionResult(false, null));
  }

  private static StoreRpc.CustomResourceResult customResourceResult(
      Optional<CustomResource> value) {
    return value
        .map(v -> new StoreRpc.CustomResourceResult(true, v))
        .orElseGet(() -> new StoreRpc.CustomResourceResult(false, null));
  }

  private static StoreRpc.JobSpecResult jobSpecResult(Optional<JobSpec> value) {
    return value
        .map(v -> new StoreRpc.JobSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.JobSpecResult(false, null));
  }

  private static StoreRpc.JobPhaseResult jobPhaseResult(Optional<JobPhase> value) {
    return value
        .map(v -> new StoreRpc.JobPhaseResult(true, v))
        .orElseGet(() -> new StoreRpc.JobPhaseResult(false, null));
  }

  private static StoreRpc.JobRunSummaryResult jobRunSummaryResult(Optional<JobRunSummary> value) {
    return value
        .map(v -> new StoreRpc.JobRunSummaryResult(true, v))
        .orElseGet(() -> new StoreRpc.JobRunSummaryResult(false, null));
  }

  private static StoreRpc.CronJobSpecResult cronJobSpecResult(Optional<CronJobSpec> value) {
    return value
        .map(v -> new StoreRpc.CronJobSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.CronJobSpecResult(false, null));
  }

  private static StoreRpc.InstantResult instantResult(Optional<java.time.Instant> value) {
    return value
        .map(v -> new StoreRpc.InstantResult(true, v.toEpochMilli()))
        .orElseGet(() -> new StoreRpc.InstantResult(false, 0L));
  }

  private static StoreRpc.DaemonSetSpecResult daemonSetSpecResult(Optional<DaemonSetSpec> value) {
    return value
        .map(v -> new StoreRpc.DaemonSetSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.DaemonSetSpecResult(false, null));
  }

  private static StoreRpc.StatefulSetSpecResult statefulSetSpecResult(
      Optional<StatefulSetSpec> value) {
    return value
        .map(v -> new StoreRpc.StatefulSetSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.StatefulSetSpecResult(false, null));
  }

  private static StoreRpc.StringResult stringResult(Optional<String> value) {
    return value
        .map(v -> new StoreRpc.StringResult(true, v))
        .orElseGet(() -> new StoreRpc.StringResult(false, ""));
  }

  private static StoreRpc.RoleResult roleResult(Optional<Role> value) {
    return value
        .map(v -> new StoreRpc.RoleResult(true, v))
        .orElseGet(() -> new StoreRpc.RoleResult(false, null));
  }

  private static StoreRpc.RoleBindingResult roleBindingResult(Optional<RoleBinding> value) {
    return value
        .map(v -> new StoreRpc.RoleBindingResult(true, v))
        .orElseGet(() -> new StoreRpc.RoleBindingResult(false, null));
  }

  private static StoreRpc.AccountResult accountResult(Optional<Account> value) {
    return value
        .map(v -> new StoreRpc.AccountResult(true, v))
        .orElseGet(() -> new StoreRpc.AccountResult(false, null));
  }

  private static StoreRpc.NodeRegistrationResult nodeRegistrationResult(
      Optional<NodeRegistration> value) {
    return value
        .map(v -> new StoreRpc.NodeRegistrationResult(true, v))
        .orElseGet(() -> new StoreRpc.NodeRegistrationResult(false, null));
  }

  private static StoreRpc.HeartbeatResult heartbeatResult(Optional<ObservedHeartbeat> value) {
    return value
        .map(v -> new StoreRpc.HeartbeatResult(true, v))
        .orElseGet(() -> new StoreRpc.HeartbeatResult(false, null));
  }

  private static StoreRpc.IntResult intResult(Optional<Integer> value) {
    return value
        .map(v -> new StoreRpc.IntResult(true, v))
        .orElseGet(() -> new StoreRpc.IntResult(false, 0));
  }

  private static StoreRpc.IntIntMapResult surgeIndicesResult(Map<Integer, Integer> surgeToTarget) {
    List<Integer> surgeIndices = new ArrayList<>();
    List<Integer> targetIndices = new ArrayList<>();
    for (Map.Entry<Integer, Integer> entry : surgeToTarget.entrySet()) {
      surgeIndices.add(entry.getKey());
      targetIndices.add(entry.getValue());
    }
    return new StoreRpc.IntIntMapResult(surgeIndices, targetIndices);
  }

  private static StoreRpc.ReconcilerInstanceStateResult reconcilerInstanceStateResult(
      Optional<ReconcilerInstanceState> value) {
    return value
        .map(v -> new StoreRpc.ReconcilerInstanceStateResult(true, v))
        .orElseGet(() -> new StoreRpc.ReconcilerInstanceStateResult(false, null));
  }

  private static StoreRpc.WorkloadHealthStateResult workloadHealthStateResult(
      Optional<WorkloadHealthState> value) {
    return value
        .map(v -> new StoreRpc.WorkloadHealthStateResult(true, v))
        .orElseGet(() -> new StoreRpc.WorkloadHealthStateResult(false, null));
  }

  private static StoreRpc.ControllerRevisionResult controllerRevisionResult(
      Optional<ControllerRevision> value) {
    return value
        .map(v -> new StoreRpc.ControllerRevisionResult(true, v))
        .orElseGet(() -> new StoreRpc.ControllerRevisionResult(false, null));
  }
}
