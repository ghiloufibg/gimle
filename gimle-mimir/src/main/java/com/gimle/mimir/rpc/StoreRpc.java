package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import java.util.List;

/**
 * The client-facing wire protocol {@code StoreClient} speaks to a {@code StoreNode} -- the store's
 * equivalent of etcd's client gRPC API, everything {@code ApiServer}/the reconcilers/{@code
 * Authorizer} used to get "for free" via a direct {@code StateStore} reference before the
 * etcd-store-extraction (see {@code claudedocs/etcd-store-extraction-design.md}). One request/
 * response record pair per {@code StateStore} method actually called outside the {@code store}
 * package, nested here the same way {@link StateMutation}'s own variants are nested in one file,
 * rather than {@link com.gimle.mimir.raft.RaftRpc}'s one-file-per-variant shape -- at this many
 * variants (StoreRpc's surface is larger than Raft's own three-RPC-kind shape), StateMutation's
 * single-file precedent is the better fit.
 *
 * <p>{@link Propose}, {@link PutHeartbeat}, {@link AcquireOrRenewLease}, and {@link ReleaseLease}
 * are the only requests that must land on the current Raft leader specifically; every other request
 * may be served by any {@code StoreNode} (design doc §4.5 -- reads stay exactly as loose as today,
 * no linearizability requirement). All four of the leader-only requests share one {@link NotLeader}
 * response for the same reason {@link com.gimle.mimir.raft.RaftNode#propose} already rejects a
 * non-leader immediately rather than silently forwarding (design doc §4.6): {@code StoreClient}
 * follows the returned leader address and retries once, rather than a {@code StoreNode} proxying
 * the write internally.
 */
public sealed interface StoreRpc {

  sealed interface Request extends StoreRpc
      permits Propose,
          PutHeartbeat,
          AcquireOrRenewLease,
          ReleaseLease,
          ListAccounts,
          GetTenant,
          GetDeployment,
          ListDeployments,
          ListAssignmentsFor,
          IsQuotaViolating,
          IsNodeCordoned,
          ListAssignments,
          ListNodeRegistrations,
          ListTenants,
          ListConfigEntriesFor,
          ListRoles,
          GetRole,
          ListRoleBindings,
          GetRoleBinding,
          GetAccount,
          GetNodeRegistration,
          GetEffectiveReplicas,
          GetRollingIndex,
          GetNodeHeartbeat,
          GetReconcilerInstanceState,
          ListReconcilerInstanceStates {}

  sealed interface Response extends StoreRpc
      permits Ok,
          NotLeader,
          LeaseResult,
          BoolResult,
          IntResult,
          DeploymentResult,
          TenantResult,
          RoleResult,
          RoleBindingResult,
          AccountResult,
          NodeRegistrationResult,
          HeartbeatResult,
          AccountListResult,
          DeploymentListResult,
          AssignmentListResult,
          NodeRegistrationListResult,
          TenantListResult,
          ConfigEntryListResult,
          RoleListResult,
          RoleBindingListResult,
          ReconcilerInstanceStateResult,
          ReconcilerInstanceStateListResult {}

  // ---- leader-only writes ----

  record Propose(StateMutation mutation) implements Request {}

  record PutHeartbeat(NodeHeartbeat heartbeat) implements Request {}

  record AcquireOrRenewLease(String name, String holderId, long ttlMillis) implements Request {}

  record ReleaseLease(String name, String holderId) implements Request {}

  // ---- reads: served by any StoreNode ----

  record ListAccounts() implements Request {}

  record GetTenant(String id) implements Request {}

  record GetDeployment(String name) implements Request {}

  record ListDeployments() implements Request {}

  record ListAssignmentsFor(String deploymentName) implements Request {}

  record IsQuotaViolating(String deploymentName) implements Request {}

  record IsNodeCordoned(String nodeId) implements Request {}

  record ListAssignments() implements Request {}

  record ListNodeRegistrations() implements Request {}

  record ListTenants() implements Request {}

  record ListConfigEntriesFor(String tenantId) implements Request {}

  record ListRoles() implements Request {}

  record GetRole(String name) implements Request {}

  record ListRoleBindings() implements Request {}

  record GetRoleBinding(String id) implements Request {}

  record GetAccount(String username) implements Request {}

  record GetNodeRegistration(String nodeId) implements Request {}

  record GetEffectiveReplicas(String deploymentName) implements Request {}

  record GetRollingIndex(String deploymentName) implements Request {}

  record GetNodeHeartbeat(String nodeId) implements Request {}

  record GetReconcilerInstanceState(String deploymentName, int instanceIndex) implements Request {}

  record ListReconcilerInstanceStates() implements Request {}

  // ---- responses ----

  /** Shared "the write succeeded, no payload" response for Propose/PutHeartbeat/ReleaseLease. */
  record Ok() implements Response {}

  /**
   * {@code leaderClientAddress} is empty when this node has no current leader hint either (a
   * mid-election gap) -- {@code StoreClient} treats that the same as any other unreachable
   * endpoint: try the next configured endpoint, matching how {@code ApiServer}'s own former
   * 307-redirect path handled an absent {@code leaderHint()} (design doc §4.6).
   */
  record NotLeader(String leaderClientAddress) implements Response {}

  record LeaseResult(boolean granted, String holderId, long expiresAtEpochMilli)
      implements Response {}

  record BoolResult(boolean value) implements Response {}

  /**
   * {@code present == false} means {@code value} is meaningless (0), matching an absent Optional.
   */
  record IntResult(boolean present, int value) implements Response {}

  record DeploymentResult(boolean present, DeploymentSpec value) implements Response {}

  record TenantResult(boolean present, Tenant value) implements Response {}

  record RoleResult(boolean present, com.gimle.core.authz.Role value) implements Response {}

  record RoleBindingResult(boolean present, RoleBinding value) implements Response {}

  record AccountResult(boolean present, Account value) implements Response {}

  record NodeRegistrationResult(boolean present, NodeRegistration value) implements Response {}

  record HeartbeatResult(boolean present, ObservedHeartbeat value) implements Response {}

  record AccountListResult(List<Account> values) implements Response {}

  record DeploymentListResult(List<DeploymentSpec> values) implements Response {}

  record AssignmentListResult(List<InstanceAssignment> values) implements Response {}

  record NodeRegistrationListResult(List<NodeRegistration> values) implements Response {}

  record TenantListResult(List<Tenant> values) implements Response {}

  record ConfigEntryListResult(List<ConfigEntry> values) implements Response {}

  record RoleListResult(List<com.gimle.core.authz.Role> values) implements Response {}

  record RoleBindingListResult(List<RoleBinding> values) implements Response {}

  record ReconcilerInstanceStateResult(boolean present, ReconcilerInstanceState value)
      implements Response {}

  record ReconcilerInstanceStateListResult(List<ReconcilerInstanceState> values)
      implements Response {}
}
