package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditTrailStatus;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.NodeHeartbeat;
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
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.util.List;
import java.util.Optional;

/**
 * The client-facing wire protocol {@code StoreClient} speaks to a {@code StoreNode} -- the store's
 * equivalent of etcd's client gRPC API, everything {@code ApiServer}/the reconcilers/{@code
 * Authorizer} used to get "for free" via a direct {@code StateStore} reference before the store
 * became its own process. One request/response record pair per {@code StateStore} method actually
 * called outside the {@code store} package, nested here the same way {@link StateMutation}'s own
 * variants are nested in one file, rather than {@link com.gimle.mimir.raft.RaftRpc}'s
 * one-file-per-variant shape -- at this many variants (StoreRpc's surface is larger than Raft's own
 * three-RPC-kind shape), StateMutation's single-file precedent is the better fit.
 *
 * <p>{@link Propose}, {@link PutHeartbeat}, {@link AcquireOrRenewLease}, {@link ReleaseLease},
 * {@link AddServer}, and {@link RemoveServer} are writes that must land on the current Raft leader
 * specifically; {@link GetNodeHeartbeat} is a leader-only *read* for a different reason -- node
 * heartbeats are deliberately never replicated through the log, so a follower's local copy is never
 * anything but empty, and answering from it the way every other read here does would be silently
 * wrong, not just stale. {@link ListConfigEntriesForLinearizable} is a third, narrower leader-only
 * *read*: unlike {@link GetNodeHeartbeat} its data is fully replicated, so any replica's answer is
 * eventually correct, but a caller whose own correctness depends on immediately reading back a
 * write it just made cannot tolerate "eventually." Every other request may be served by any {@code
 * StoreNode} -- reads stay exactly as loose as today, no linearizability requirement. Every
 * leader-only request shares one {@link NotLeader} response for the same reason {@link
 * com.gimle.mimir.raft.RaftNode#propose} already rejects a non-leader immediately rather than
 * silently forwarding: {@code StoreClient} follows the returned leader address and retries once,
 * rather than a {@code StoreNode} proxying the write internally.
 */
public sealed interface StoreRpc {

  sealed interface Request extends StoreRpc
      permits Propose,
          PutHeartbeat,
          AcquireOrRenewLease,
          ReleaseLease,
          AddServer,
          RemoveServer,
          ListAccounts,
          GetTenant,
          GetDeployment,
          GetDeploymentGeneration,
          ListDeployments,
          GetService,
          ListServices,
          GetNetworkPolicy,
          ListNetworkPolicies,
          GetIngress,
          ListIngresses,
          GetAlertRule,
          ListAlertRules,
          ListAssignmentsFor,
          IsQuotaViolating,
          IsNodeCordoned,
          GetNodeTaints,
          IsCertificateRevoked,
          ListRevokedCertificateSerials,
          GetSessionRevokedBeforeEpochMilli,
          GetWorkloadToken,
          ListAssignments,
          GetJobSpec,
          ListJobSpecs,
          ListJobRunsFor,
          ListJobRuns,
          GetJobPhase,
          GetJobRunSummary,
          GetCronJobSpec,
          ListCronJobSpecs,
          GetCronJobLastSchedule,
          GetDaemonSetSpec,
          ListDaemonSetSpecs,
          ListDaemonSetAssignments,
          ListDaemonSetAssignmentsFor,
          ListRollingDaemonSetNodes,
          GetStatefulSetSpec,
          ListStatefulSetSpecs,
          ListStatefulSetAssignments,
          ListStatefulSetAssignmentsFor,
          GetRollingStatefulSetIndex,
          GetStatefulSetIndexNode,
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
          GetDeploymentLastScale,
          ListRollingIndices,
          ListSurgeIndices,
          GetNodeHeartbeat,
          GetSnapshot,
          ListConfigEntriesForLinearizable,
          GetReconcilerInstanceState,
          ListReconcilerInstanceStates,
          GetWorkloadHealthState,
          ListWorkloadHealthStates,
          ListInstanceEvents,
          ListAuditEvents,
          GetAuditTrailStatus,
          ListControllerRevisions,
          GetControllerRevision,
          GetLimitRange,
          ListLimitRanges,
          IsLimitRangeViolating,
          GetLimitRangeViolationReason,
          ListKindDefinitions,
          GetKindDefinition,
          ListCustomResources,
          ListCustomResourcesFor,
          GetCustomResource,
          Status {}

  sealed interface Response extends StoreRpc
      permits Ok,
          NotLeader,
          MutationRejected,
          LeaseResult,
          BoolResult,
          IntResult,
          DeploymentResult,
          GenerationResult,
          JobSpecResult,
          JobSpecListResult,
          JobRunListResult,
          JobPhaseResult,
          JobRunSummaryResult,
          CronJobSpecResult,
          CronJobSpecListResult,
          InstantResult,
          DaemonSetSpecResult,
          DaemonSetSpecListResult,
          DaemonSetAssignmentListResult,
          StatefulSetSpecResult,
          StatefulSetSpecListResult,
          StatefulSetAssignmentListResult,
          StringResult,
          IntSetResult,
          IntIntMapResult,
          StringSetResult,
          TenantResult,
          RoleResult,
          RoleBindingResult,
          AccountResult,
          NodeRegistrationResult,
          WorkloadTokenResult,
          HeartbeatResult,
          SnapshotResult,
          AccountListResult,
          DeploymentListResult,
          ServiceResult,
          ServiceListResult,
          NetworkPolicyResult,
          NetworkPolicyListResult,
          IngressResult,
          IngressListResult,
          AlertRuleResult,
          AlertRuleListResult,
          AssignmentListResult,
          NodeRegistrationListResult,
          TenantListResult,
          ConfigEntryListResult,
          RoleListResult,
          RoleBindingListResult,
          ReconcilerInstanceStateResult,
          ReconcilerInstanceStateListResult,
          WorkloadHealthStateResult,
          WorkloadHealthStateListResult,
          InstanceEventListResult,
          AuditEventListResult,
          AuditTrailStatusResult,
          ControllerRevisionListResult,
          ControllerRevisionResult,
          LimitRangeResult,
          LimitRangeListResult,
          KindDefinitionResult,
          KindDefinitionListResult,
          CustomResourceResult,
          CustomResourceListResult,
          StatusResult {}

  // ---- leader-only requests (writes, plus the one leader-local read) ----

  record Propose(StateMutation mutation) implements Request {}

  record PutHeartbeat(NodeHeartbeat heartbeat) implements Request {}

  record AcquireOrRenewLease(String name, String holderId, long ttlMillis) implements Request {}

  record ReleaseLease(String name, String holderId) implements Request {}

  /**
   * Adds {@code peerId} (reachable at {@code host}/{@code raftPort}/{@code clientPort}) to the
   * cluster's Raft membership -- etcd-style, one server at a time. Leader-only, same {@link
   * NotLeader}-redirect posture as {@link Propose}; {@code StoreClient} maps any rejection (already
   * a member, another change still in flight, or a genuine non-leader) onto the same
   * retry-the-leader-hint path {@link Propose} already uses, rather than a new response shape per
   * rejection reason.
   */
  record AddServer(String peerId, String host, int raftPort, int clientPort) implements Request {}

  /** The symmetric removal counterpart to {@link AddServer}. */
  record RemoveServer(String peerId) implements Request {}

  /**
   * The one leader-only *read* in this group: node heartbeats are deliberately never replicated
   * through the Raft log (too high-frequency, tolerant of a brief gap after a leader change -- see
   * {@code StateStore.putNodeHeartbeat}'s own javadoc), so a follower's local copy is never
   * anything but empty. Routing this through the leader the same way a write would be is what makes
   * the answer actually correct instead of merely available.
   */
  record GetNodeHeartbeat(String nodeId) implements Request {}

  /**
   * A full-state snapshot, taken from the current leader specifically so a caller backing up the
   * cluster gets a point-in-time view that's never stale by a not-yet-caught-up follower's replay
   * lag -- the same "why leader-only" reasoning {@link GetNodeHeartbeat} and {@link
   * ListConfigEntriesForLinearizable} each give for their own different reasons. Answered by
   * encoding {@code StateStore#snapshot()} via {@code RaftCodec#encodeSnapshot}; restoring one back
   * goes through the ordinary replicated {@link Propose} path instead (as a {@code
   * StateMutation.RestoreSnapshot}), not a dedicated request here, so every replica applies it the
   * same way any other mutation is applied rather than one node's local state silently diverging
   * from the rest of the cluster.
   */
  record GetSnapshot() implements Request {}

  /**
   * Same query and same {@link ConfigEntryListResult} response shape as {@link
   * ListConfigEntriesFor}, but leader-routed: a caller whose own correctness depends on reading
   * back a write it just made through this same client (see {@code SecretStore.put}'s optimistic
   * before/after version check) cannot rely on {@link ListConfigEntriesFor}'s round-robin routing,
   * which may land on a follower that has not yet replicated that write -- silently stale, not
   * merely slow, the same failure mode {@link GetNodeHeartbeat} exists to avoid for a different
   * reason. {@link ListConfigEntriesFor} itself stays any-node-servable and unchanged for every
   * other caller, which has no such requirement.
   */
  record ListConfigEntriesForLinearizable(String tenantId) implements Request {}

  // ---- reads: served by any StoreNode ----

  record ListAccounts() implements Request {}

  record GetTenant(String id) implements Request {}

  record GetDeployment(Optional<String> tenantId, String name) implements Request {}

  /**
   * The compare-and-set precondition read for {@code ApiServer}'s deployment apply/delete/rollback
   * handlers -- see {@link StateMutation.PutDeployment}/{@link StateMutation.RemoveDeployment}'s
   * own javadoc. Any-node-servable like every other plain read here: a stale answer only ever
   * causes a spurious, safe rejection at the actual CAS check inside {@code applyTo} (evaluated
   * against the true replicated state, not this read), never an incorrect write.
   */
  record GetDeploymentGeneration(Optional<String> tenantId, String name) implements Request {}

  record ListDeployments() implements Request {}

  record GetService(Optional<String> tenantId, String name) implements Request {}

  record ListServices() implements Request {}

  record GetNetworkPolicy(String tenantId, String name) implements Request {}

  record GetIngress(String tenantId, String name) implements Request {}

  record ListIngresses() implements Request {}

  record ListNetworkPolicies() implements Request {}

  record GetAlertRule(Optional<String> tenantId, String name) implements Request {}

  record ListAlertRules() implements Request {}

  record GetLimitRange(String tenantId) implements Request {}

  record ListLimitRanges() implements Request {}

  record ListAssignmentsFor(Optional<String> tenantId, String deploymentName) implements Request {}

  record IsQuotaViolating(Optional<String> tenantId, String deploymentName) implements Request {}

  record IsLimitRangeViolating(Optional<String> tenantId, String deploymentName)
      implements Request {}

  /** Response reuses {@link StringResult} -- same shape as {@link GetStatefulSetIndexNode}'s. */
  record GetLimitRangeViolationReason(Optional<String> tenantId, String deploymentName)
      implements Request {}

  record IsNodeCordoned(String nodeId) implements Request {}

  /**
   * Response reuses {@link StringSetResult} -- same shape as {@link ListRollingDaemonSetNodes}'s.
   */
  record GetNodeTaints(String nodeId) implements Request {}

  record IsCertificateRevoked(String serialNumber) implements Request {}

  record ListRevokedCertificateSerials() implements Request {}

  /**
   * Response reuses {@link GenerationResult} -- same shape as {@link GetDeploymentGeneration}'s.
   */
  record GetSessionRevokedBeforeEpochMilli(String username) implements Request {}

  record GetWorkloadToken(String key) implements Request {}

  record ListAssignments() implements Request {}

  record GetJobSpec(Optional<String> tenantId, String name) implements Request {}

  record ListJobSpecs() implements Request {}

  record ListJobRunsFor(Optional<String> tenantId, String jobName) implements Request {}

  record ListJobRuns() implements Request {}

  /** Empty means "not yet terminal" -- see {@code StateStore#jobPhases}'s own field javadoc. */
  record GetJobPhase(Optional<String> tenantId, String jobName) implements Request {}

  record GetJobRunSummary(Optional<String> tenantId, String jobName) implements Request {}

  record GetCronJobSpec(Optional<String> tenantId, String name) implements Request {}

  record ListCronJobSpecs() implements Request {}

  /** Empty means "never fired yet" -- see {@code StateStore#cronJobLastSchedule}'s own javadoc. */
  record GetCronJobLastSchedule(Optional<String> tenantId, String name) implements Request {}

  record GetDaemonSetSpec(Optional<String> tenantId, String name) implements Request {}

  record ListDaemonSetSpecs() implements Request {}

  record ListDaemonSetAssignments() implements Request {}

  record ListDaemonSetAssignmentsFor(Optional<String> tenantId, String daemonSetName)
      implements Request {}

  record ListRollingDaemonSetNodes(Optional<String> tenantId, String daemonSetName)
      implements Request {}

  record GetStatefulSetSpec(Optional<String> tenantId, String name) implements Request {}

  record ListStatefulSetSpecs() implements Request {}

  record ListStatefulSetAssignments() implements Request {}

  record ListStatefulSetAssignmentsFor(Optional<String> tenantId, String statefulSetName)
      implements Request {}

  record GetRollingStatefulSetIndex(Optional<String> tenantId, String statefulSetName)
      implements Request {}

  record GetStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) implements Request {}

  record ListNodeRegistrations() implements Request {}

  record ListTenants() implements Request {}

  record ListConfigEntriesFor(String tenantId) implements Request {}

  record ListRoles() implements Request {}

  record GetRole(String name) implements Request {}

  record ListRoleBindings() implements Request {}

  record GetRoleBinding(String id) implements Request {}

  record GetAccount(String username) implements Request {}

  record GetNodeRegistration(String nodeId) implements Request {}

  record GetEffectiveReplicas(Optional<String> tenantId, String deploymentName)
      implements Request {}

  /** Empty means "never scaled" -- see {@code StateStore#deploymentLastScale}'s own comment. */
  record GetDeploymentLastScale(Optional<String> tenantId, String deploymentName)
      implements Request {}

  record ListRollingIndices(Optional<String> tenantId, String deploymentName) implements Request {}

  record ListSurgeIndices(Optional<String> tenantId, String deploymentName) implements Request {}

  record GetReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex) implements Request {}

  record ListReconcilerInstanceStates() implements Request {}

  record GetWorkloadHealthState(
      Optional<String> tenantId, String workloadKind, String workloadName, String slot)
      implements Request {}

  record ListWorkloadHealthStates() implements Request {}

  record ListInstanceEvents(Optional<String> tenantId, String deploymentName, int instanceIndex)
      implements Request {}

  /**
   * The answering node's own view of the cluster: its Raft id, whether it currently believes itself
   * leader, its leader hint, and the membership it is configured with -- etcd's own client API
   * exposes exactly this, and for the same reason: leader-aware operators and tooling otherwise
   * have no way to ask "who leads?" or "who is a member?" without attempting a write and fishing
   * the answer out of a {@link NotLeader} redirect. A plain node-local read, served by any node;
   * the answer is that node's view, which mid-election may briefly name no leader.
   */
  record ListKindDefinitions() implements Request {}

  /** {@code kindName} is always the stored, prefixed form ({@code custom.Greeting}). */
  record GetKindDefinition(String kindName) implements Request {}

  record ListCustomResources(String kindName) implements Request {}

  record ListCustomResourcesFor(String kindName, Optional<String> tenantId) implements Request {}

  record GetCustomResource(String kindName, Optional<String> tenantId, String name)
      implements Request {}

  record Status() implements Request {}

  /**
   * The first {@code Request} variant with optional fields -- every filter defaults to "match
   * everything for that dimension" the same way {@link com.gimle.mimir.store.StoreReader
   * #listAuditEvents}'s own {@code Optional} parameters do; {@code StoreCodec} encodes each via
   * {@code DomainCodec.writeOptionalString}/{@code writeOptionalLong}.
   */
  record ListAuditEvents(
      Optional<String> principal,
      Optional<String> resourceKind,
      Optional<String> tenantId,
      Optional<Long> since)
      implements Request {}

  /**
   * The trail's own retention state -- see {@link AuditTrailStatus}'s own javadoc for why this is a
   * separate request from {@link ListAuditEvents} rather than a field tacked onto its response: the
   * status describes the *whole* trail regardless of query filters, while a {@link ListAuditEvents}
   * answer may be a filtered subset of it.
   */
  record GetAuditTrailStatus() implements Request {}

  record ListControllerRevisions(String workloadKind, Optional<String> tenantId, String name)
      implements Request {}

  record GetControllerRevision(
      String workloadKind, Optional<String> tenantId, String name, int revision)
      implements Request {}

  // ---- responses ----

  /** Shared "the write succeeded, no payload" response for Propose/PutHeartbeat/ReleaseLease. */
  record Ok() implements Response {}

  /**
   * {@code leaderClientAddress} is empty when this node has no current leader hint either (a
   * mid-election gap) -- {@code StoreClient} treats that the same as any other unreachable
   * endpoint: try the next configured endpoint, matching how {@code ApiServer}'s own former
   * 307-redirect path handled an absent {@code leaderHint()}.
   */
  record NotLeader(String leaderClientAddress) implements Response {}

  /**
   * {@link Propose} rejected a {@link StateMutation.PutDeployment}/{@link
   * StateMutation.RemoveDeployment}'s own generation precondition -- deliberately not folded into
   * {@link NotLeader}: retrying against the correct leader would just reject identically, unlike a
   * genuine not-leader redirect, which retrying elsewhere resolves. {@code StoreClient} surfaces
   * this as a real, expected {@code MutationOutcome.Rejected} value, not an exception.
   */
  record MutationRejected(String reason) implements Response {}

  record LeaseResult(boolean granted, String holderId, long expiresAtEpochMilli)
      implements Response {}

  record BoolResult(boolean value) implements Response {}

  /**
   * {@code present == false} means {@code value} is meaningless (0), matching an absent Optional.
   */
  record IntResult(boolean present, int value) implements Response {}

  record DeploymentResult(boolean present, DeploymentSpec value) implements Response {}

  /**
   * Answers {@link GetDeploymentGeneration} -- 0 means never-existed-or-fully-removed, never
   * absent.
   */
  record GenerationResult(long value) implements Response {}

  record JobSpecResult(boolean present, JobSpec value) implements Response {}

  record JobSpecListResult(List<JobSpec> values) implements Response {}

  record JobRunListResult(List<JobRun> values) implements Response {}

  /**
   * {@code present == false} means "not yet terminal," matching {@code Optional<JobPhase>}'s own
   * absence -- {@code value} is meaningless ({@code JobPhase.RUNNING}) when {@code present} is
   * {@code false}, the same "meaningless placeholder" convention {@link IntResult} already uses.
   */
  record JobPhaseResult(boolean present, JobPhase value) implements Response {}

  /** {@code present == false} means the job has no terminal run summary recorded (yet). */
  record JobRunSummaryResult(boolean present, JobRunSummary value) implements Response {}

  record CronJobSpecResult(boolean present, CronJobSpec value) implements Response {}

  record CronJobSpecListResult(List<CronJobSpec> values) implements Response {}

  /**
   * {@code present == false} means "never fired yet," matching {@code Optional<Instant>}'s own
   * absence -- {@code value} is meaningless (0) when {@code present} is {@code false}, the same
   * "meaningless placeholder" convention {@link IntResult} already uses.
   */
  record InstantResult(boolean present, long epochMilli) implements Response {}

  record DaemonSetSpecResult(boolean present, DaemonSetSpec value) implements Response {}

  record DaemonSetSpecListResult(List<DaemonSetSpec> values) implements Response {}

  record DaemonSetAssignmentListResult(List<DaemonSetAssignment> values) implements Response {}

  record StatefulSetSpecResult(boolean present, StatefulSetSpec value) implements Response {}

  record StatefulSetSpecListResult(List<StatefulSetSpec> values) implements Response {}

  record StatefulSetAssignmentListResult(List<StatefulSetAssignment> values) implements Response {}

  /**
   * {@code present == false} means the value is absent, matching {@code Optional<String>}'s own
   * absence -- {@code value} is {@code ""} when {@code present} is {@code false}, the same
   * "meaningless placeholder" convention {@link IntResult}/{@link InstantResult} already use. Every
   * other optional {@code String} field in this file so far has ridden along inside a larger record
   * ({@code NotLeader}'s own address), so this is the first standalone one.
   */
  record StringResult(boolean present, String value) implements Response {}

  /**
   * The small, bounded in-flight index set for {@link ListRollingIndices} -- unlike {@link
   * IntResult}, there is no "absent" case to model: an empty {@code values} list means "no rollout
   * in flight," matching {@code StoreReader#getRollingIndices}'s own empty-set-not-Optional shape.
   */
  record IntSetResult(List<Integer> values) implements Response {}

  /**
   * The (surgeIndex -&gt; targetIndex) mapping for {@link ListSurgeIndices} -- parallel lists
   * rather than a single {@code Map}-typed field, matching every other list-shaped {@code Response}
   * record's wire shape; {@code surgeIndices.get(i)} maps to {@code targetIndices.get(i)}.
   */
  record IntIntMapResult(List<Integer> surgeIndices, List<Integer> targetIndices)
      implements Response {}

  /** The node-keyed counterpart to {@link IntSetResult}, for {@link ListRollingDaemonSetNodes}. */
  record StringSetResult(List<String> values) implements Response {}

  record TenantResult(boolean present, Tenant value) implements Response {}

  record RoleResult(boolean present, Role value) implements Response {}

  record RoleBindingResult(boolean present, RoleBinding value) implements Response {}

  record AccountResult(boolean present, Account value) implements Response {}

  record NodeRegistrationResult(boolean present, NodeRegistration value) implements Response {}

  record WorkloadTokenResult(boolean present, WorkloadTokenRecord value) implements Response {}

  record HeartbeatResult(boolean present, ObservedHeartbeat value) implements Response {}

  /** {@code snapshot} is {@code RaftCodec#encodeSnapshot}'s own already-versioned encoding. */
  record SnapshotResult(byte[] snapshot) implements Response {}

  record AccountListResult(List<Account> values) implements Response {}

  record DeploymentListResult(List<DeploymentSpec> values) implements Response {}

  record ServiceResult(boolean present, ServiceSpec value) implements Response {}

  record ServiceListResult(List<ServiceSpec> values) implements Response {}

  record NetworkPolicyResult(boolean present, NetworkPolicySpec value) implements Response {}

  record NetworkPolicyListResult(List<NetworkPolicySpec> values) implements Response {}

  record IngressResult(boolean present, IngressSpec value) implements Response {}

  record IngressListResult(List<IngressSpec> values) implements Response {}

  record AlertRuleResult(boolean present, AlertRuleSpec value) implements Response {}

  record AlertRuleListResult(List<AlertRuleSpec> values) implements Response {}

  record AssignmentListResult(List<InstanceAssignment> values) implements Response {}

  record NodeRegistrationListResult(List<NodeRegistration> values) implements Response {}

  record TenantListResult(List<Tenant> values) implements Response {}

  record ConfigEntryListResult(List<ConfigEntry> values) implements Response {}

  record RoleListResult(List<Role> values) implements Response {}

  record RoleBindingListResult(List<RoleBinding> values) implements Response {}

  record ReconcilerInstanceStateResult(boolean present, ReconcilerInstanceState value)
      implements Response {}

  record ReconcilerInstanceStateListResult(List<ReconcilerInstanceState> values)
      implements Response {}

  record WorkloadHealthStateResult(boolean present, WorkloadHealthState value)
      implements Response {}

  record WorkloadHealthStateListResult(List<WorkloadHealthState> values) implements Response {}

  record InstanceEventListResult(List<InstanceEvent> values) implements Response {}

  record AuditEventListResult(List<AuditEvent> values) implements Response {}

  record AuditTrailStatusResult(AuditTrailStatus status) implements Response {}

  record ControllerRevisionListResult(List<ControllerRevision> values) implements Response {}

  record ControllerRevisionResult(boolean present, ControllerRevision value) implements Response {}

  record LimitRangeResult(boolean present, LimitRangeSpec value) implements Response {}

  record LimitRangeListResult(List<LimitRangeSpec> values) implements Response {}

  record KindDefinitionResult(boolean present, KindDefinitionSpec value) implements Response {}

  record KindDefinitionListResult(List<KindDefinitionSpec> values) implements Response {}

  record CustomResourceResult(boolean present, CustomResource value) implements Response {}

  record CustomResourceListResult(List<CustomResource> values) implements Response {}

  /**
   * {@code leaderId} is {@code ""} when the answering node has no current leader hint (a
   * mid-election gap), the same empty-string convention {@link NotLeader} uses. {@code memberIds}
   * is the answering node's currently configured membership, itself included.
   */
  record StatusResult(String selfId, boolean leader, String leaderId, List<String> memberIds)
      implements Response {}
}
