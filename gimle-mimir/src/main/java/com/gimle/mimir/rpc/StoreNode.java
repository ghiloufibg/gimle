package com.gimle.mimir.rpc;

import com.gimle.core.exception.GimleRaftException;
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
 * StoreRpc.PutHeartbeat}/{@link StoreRpc.AcquireOrRenewLease}/{@link StoreRpc.ReleaseLease},
 * through a leader check first, translating a non-leader into {@link StoreRpc.NotLeader} carrying
 * the leader's *client* address rather than its Raft ID -- resolved via {@code
 * raftIdToClientAddress}, built at construction time from {@code --peers host:raftPort:clientPort}
 * the same way {@code ControlPlaneMain} builds its own {@code peerApiAddresses} map today (design
 * doc §4.6).
 */
public final class StoreNode implements StoreRpcHandler {

  private final RaftNode raftNode;
  private final StateStore store;
  private final Map<String, String> raftIdToClientAddress;

  public StoreNode(RaftNode raftNode, StateStore store, Map<String, String> raftIdToClientAddress) {
    this.raftNode = raftNode;
    this.store = store;
    this.raftIdToClientAddress = Map.copyOf(raftIdToClientAddress);
  }

  @Override
  public StoreRpc.Response handle(StoreRpc.Request request) {
    return switch (request) {
      case StoreRpc.Propose r -> handlePropose(r);
      case StoreRpc.PutHeartbeat r -> handlePutHeartbeat(r);
      case StoreRpc.AcquireOrRenewLease r -> handleAcquireOrRenewLease(r);
      case StoreRpc.ReleaseLease r -> handleReleaseLease(r);
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
      case StoreRpc.GetNodeHeartbeat r -> heartbeatResult(store.getNodeHeartbeat(r.nodeId()));
      case StoreRpc.GetReconcilerInstanceState r ->
          reconcilerInstanceStateResult(
              store.getReconcilerInstanceState(r.deploymentName(), r.instanceIndex()));
      case StoreRpc.ListReconcilerInstanceStates r ->
          new StoreRpc.ReconcilerInstanceStateListResult(store.listReconcilerInstanceStates());
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
