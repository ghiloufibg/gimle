package com.gimle.mimir.rpc;

import com.gimle.core.exception.GimleRaftException;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.RaftNode;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.mimir.store.StateStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The server side of {@link StoreRpc}: wraps an already-constructed {@link RaftNode} + {@link
 * StateStore}, dispatching every request either straight to a {@code StateStore} getter (any node
 * may answer -- design doc §4.5) or, for {@link StoreRpc.Propose}/{@link
 * StoreRpc.PutHeartbeat}/{@link StoreRpc.AcquireOrRenewLease}/{@link StoreRpc.ReleaseLease}/{@link
 * StoreRpc.AddServer}/{@link StoreRpc.RemoveServer}/{@link StoreRpc.GetNodeHeartbeat}, through a
 * leader check first, translating a non-leader into {@link StoreRpc.NotLeader} carrying the
 * leader's *client* address rather than its Raft ID -- resolved via {@code raftIdToClientAddress}.
 * {@link StoreRpc.GetNodeHeartbeat} is the one *read* in this leader-only group: node heartbeats
 * are deliberately never replicated through the Raft log (too high-frequency, and tolerant of a
 * brief gap after a leader change -- see {@code StateStore.putNodeHeartbeat}'s own javadoc), so a
 * follower's local copy is never anything but empty. Treating it like every other any-node read
 * would silently answer "no heartbeat" from a replica that never held leadership, forever -- this
 * routes it the same leader-only way as everything else that's leader-local state (P2-14). Unlike
 * every other field here, {@code raftIdToClientAddress} is a *live* reference the caller ({@code
 * StoreMain}) keeps mutating as membership changes -- {@code StoreNode} takes no defensive copy of
 * it on purpose, so a peer added after this node was constructed still resolves correctly (design
 * doc §4.6).
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
      case StoreRpc.GetDeployment r -> deploymentResult(store.getDeployment(r.name()));
      case StoreRpc.ListDeployments r -> new StoreRpc.DeploymentListResult(store.listDeployments());
      case StoreRpc.ListAssignmentsFor r ->
          new StoreRpc.AssignmentListResult(store.listAssignmentsFor(r.deploymentName()));
      case StoreRpc.IsQuotaViolating r ->
          new StoreRpc.BoolResult(store.isQuotaViolating(r.deploymentName()));
      case StoreRpc.IsNodeCordoned r -> new StoreRpc.BoolResult(store.isNodeCordoned(r.nodeId()));
      case StoreRpc.ListAssignments r -> new StoreRpc.AssignmentListResult(store.listAssignments());
      case StoreRpc.GetJobSpec r -> jobSpecResult(store.getJobSpec(r.name()));
      case StoreRpc.ListJobSpecs r -> new StoreRpc.JobSpecListResult(store.listJobSpecs());
      case StoreRpc.ListJobRunsFor r ->
          new StoreRpc.JobRunListResult(store.listJobRunsFor(r.jobName()));
      case StoreRpc.ListJobRuns r -> new StoreRpc.JobRunListResult(store.listJobRuns());
      case StoreRpc.GetJobPhase r -> jobPhaseResult(store.getJobPhase(r.jobName()));
      case StoreRpc.GetCronJobSpec r -> cronJobSpecResult(store.getCronJobSpec(r.name()));
      case StoreRpc.ListCronJobSpecs r ->
          new StoreRpc.CronJobSpecListResult(store.listCronJobSpecs());
      case StoreRpc.GetCronJobLastSchedule r ->
          instantResult(store.getCronJobLastSchedule(r.name()));
      case StoreRpc.GetDaemonSetSpec r -> daemonSetSpecResult(store.getDaemonSetSpec(r.name()));
      case StoreRpc.ListDaemonSetSpecs r ->
          new StoreRpc.DaemonSetSpecListResult(store.listDaemonSetSpecs());
      case StoreRpc.ListDaemonSetAssignments r ->
          new StoreRpc.DaemonSetAssignmentListResult(store.listDaemonSetAssignments());
      case StoreRpc.ListDaemonSetAssignmentsFor r ->
          new StoreRpc.DaemonSetAssignmentListResult(
              store.listDaemonSetAssignmentsFor(r.daemonSetName()));
      case StoreRpc.GetRollingDaemonSetNode r ->
          stringResult(store.getRollingDaemonSetNode(r.daemonSetName()));
      case StoreRpc.GetStatefulSetSpec r ->
          statefulSetSpecResult(store.getStatefulSetSpec(r.name()));
      case StoreRpc.ListStatefulSetSpecs r ->
          new StoreRpc.StatefulSetSpecListResult(store.listStatefulSetSpecs());
      case StoreRpc.ListStatefulSetAssignments r ->
          new StoreRpc.StatefulSetAssignmentListResult(store.listStatefulSetAssignments());
      case StoreRpc.ListStatefulSetAssignmentsFor r ->
          new StoreRpc.StatefulSetAssignmentListResult(
              store.listStatefulSetAssignmentsFor(r.statefulSetName()));
      case StoreRpc.GetRollingStatefulSetIndex r ->
          intResult(store.getRollingStatefulSetIndex(r.statefulSetName()));
      case StoreRpc.GetStatefulSetIndexNode r ->
          stringResult(store.getStatefulSetIndexNode(r.statefulSetName(), r.instanceIndex()));
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
          intResult(store.getEffectiveReplicas(r.deploymentName()));
      case StoreRpc.GetRollingIndex r -> intResult(store.getRollingIndex(r.deploymentName()));
      case StoreRpc.GetNodeHeartbeat r -> handleGetNodeHeartbeat(r);
      case StoreRpc.GetReconcilerInstanceState r ->
          reconcilerInstanceStateResult(
              store.getReconcilerInstanceState(r.deploymentName(), r.instanceIndex()));
      case StoreRpc.ListReconcilerInstanceStates r ->
          new StoreRpc.ReconcilerInstanceStateListResult(store.listReconcilerInstanceStates());
      case StoreRpc.ListInstanceEvents r ->
          new StoreRpc.InstanceEventListResult(
              store.listInstanceEvents(r.deploymentName(), r.instanceIndex()));
      case StoreRpc.ListAuditEvents r ->
          new StoreRpc.AuditEventListResult(
              store.listAuditEvents(r.principal(), r.resourceKind(), r.tenantId(), r.since()));
    };
  }

  private StoreRpc.Response handlePropose(StoreRpc.Propose request) {
    try {
      raftNode.propose(request.mutation());
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
   * Leader-only, per this class's own javadoc (P2-14): a follower's local heartbeat map is never
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

  private static StoreRpc.TenantResult tenantResult(Optional<com.gimle.core.tenant.Tenant> value) {
    return value
        .map(v -> new StoreRpc.TenantResult(true, v))
        .orElseGet(() -> new StoreRpc.TenantResult(false, null));
  }

  private static StoreRpc.DeploymentResult deploymentResult(
      Optional<com.gimle.mimir.manifest.DeploymentSpec> value) {
    return value
        .map(v -> new StoreRpc.DeploymentResult(true, v))
        .orElseGet(() -> new StoreRpc.DeploymentResult(false, null));
  }

  private static StoreRpc.JobSpecResult jobSpecResult(
      Optional<com.gimle.mimir.manifest.JobSpec> value) {
    return value
        .map(v -> new StoreRpc.JobSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.JobSpecResult(false, null));
  }

  private static StoreRpc.JobPhaseResult jobPhaseResult(
      Optional<com.gimle.mimir.store.JobPhase> value) {
    return value
        .map(v -> new StoreRpc.JobPhaseResult(true, v))
        .orElseGet(() -> new StoreRpc.JobPhaseResult(false, null));
  }

  private static StoreRpc.CronJobSpecResult cronJobSpecResult(
      Optional<com.gimle.mimir.manifest.CronJobSpec> value) {
    return value
        .map(v -> new StoreRpc.CronJobSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.CronJobSpecResult(false, null));
  }

  private static StoreRpc.InstantResult instantResult(Optional<java.time.Instant> value) {
    return value
        .map(v -> new StoreRpc.InstantResult(true, v.toEpochMilli()))
        .orElseGet(() -> new StoreRpc.InstantResult(false, 0L));
  }

  private static StoreRpc.DaemonSetSpecResult daemonSetSpecResult(
      Optional<com.gimle.mimir.manifest.DaemonSetSpec> value) {
    return value
        .map(v -> new StoreRpc.DaemonSetSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.DaemonSetSpecResult(false, null));
  }

  private static StoreRpc.StatefulSetSpecResult statefulSetSpecResult(
      Optional<com.gimle.mimir.manifest.StatefulSetSpec> value) {
    return value
        .map(v -> new StoreRpc.StatefulSetSpecResult(true, v))
        .orElseGet(() -> new StoreRpc.StatefulSetSpecResult(false, null));
  }

  private static StoreRpc.StringResult stringResult(Optional<String> value) {
    return value
        .map(v -> new StoreRpc.StringResult(true, v))
        .orElseGet(() -> new StoreRpc.StringResult(false, ""));
  }

  private static StoreRpc.RoleResult roleResult(Optional<com.gimle.core.authz.Role> value) {
    return value
        .map(v -> new StoreRpc.RoleResult(true, v))
        .orElseGet(() -> new StoreRpc.RoleResult(false, null));
  }

  private static StoreRpc.RoleBindingResult roleBindingResult(
      Optional<com.gimle.core.authz.RoleBinding> value) {
    return value
        .map(v -> new StoreRpc.RoleBindingResult(true, v))
        .orElseGet(() -> new StoreRpc.RoleBindingResult(false, null));
  }

  private static StoreRpc.AccountResult accountResult(
      Optional<com.gimle.core.authz.Account> value) {
    return value
        .map(v -> new StoreRpc.AccountResult(true, v))
        .orElseGet(() -> new StoreRpc.AccountResult(false, null));
  }

  private static StoreRpc.NodeRegistrationResult nodeRegistrationResult(
      Optional<com.gimle.core.protocol.NodeRegistration> value) {
    return value
        .map(v -> new StoreRpc.NodeRegistrationResult(true, v))
        .orElseGet(() -> new StoreRpc.NodeRegistrationResult(false, null));
  }

  private static StoreRpc.HeartbeatResult heartbeatResult(
      Optional<com.gimle.mimir.store.ObservedHeartbeat> value) {
    return value
        .map(v -> new StoreRpc.HeartbeatResult(true, v))
        .orElseGet(() -> new StoreRpc.HeartbeatResult(false, null));
  }

  private static StoreRpc.IntResult intResult(Optional<Integer> value) {
    return value
        .map(v -> new StoreRpc.IntResult(true, v))
        .orElseGet(() -> new StoreRpc.IntResult(false, 0));
  }

  private static StoreRpc.ReconcilerInstanceStateResult reconcilerInstanceStateResult(
      Optional<com.gimle.mimir.store.ReconcilerInstanceState> value) {
    return value
        .map(v -> new StoreRpc.ReconcilerInstanceStateResult(true, v))
        .orElseGet(() -> new StoreRpc.ReconcilerInstanceStateResult(false, null));
  }
}
