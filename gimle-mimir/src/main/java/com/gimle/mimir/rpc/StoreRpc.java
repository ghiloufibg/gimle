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
import com.gimle.mimir.store.RequestOutcomeRecord;
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
 * specifically. So does every read here, for a different reason: only the leader can establish a
 * read index, and only an answer taken against one reflects everything committed cluster-wide
 * before the request arrived rather than however far one replica has caught up. {@link Status} is
 * the single exception any {@code StoreNode} answers for itself -- it reports that node's own
 * belief about leadership rather than reading replicated state at all. Every leader-only request
 * shares one {@link NotLeader} response for the same reason {@link
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
          GetAlertFiringState,
          ListAssignmentsFor,
          IsQuotaViolating,
          IsNodeCordoned,
          GetNodeTaints,
          IsCertificateRevoked,
          ListRevokedCertificateSerials,
          IsSecretsKeyRetired,
          ListRetiredSecretsKeyIds,
          GetSessionRevokedBeforeEpochMilli,
          GetWorkloadToken,
          GetRequestOutcome,
          CountRequestOutcomesBefore,
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
          GetDaemonSetDesiredCount,
          GetStatefulSetSpec,
          ListStatefulSetSpecs,
          ListStatefulSetAssignments,
          ListStatefulSetAssignmentsFor,
          ListRollingStatefulSetIndices,
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
          GetNodeObservationWindow,
          GetSnapshot,
          GetReconcilerInstanceState,
          ListReconcilerInstanceStates,
          GetWorkloadHealthState,
          ListWorkloadHealthStates,
          ListInstanceEvents,
          ListAllInstanceEvents,
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
          RequestOutcomeResult,
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
          AlertFiringStateResult,
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
   * Leader-routed like every read here, but it would have to be even if none of the others were:
   * node heartbeats are deliberately never replicated through the Raft log (too high-frequency,
   * tolerant of a brief gap after a leader change -- see {@code StateStore.putNodeHeartbeat}'s own
   * javadoc), so a follower's local copy is never anything but empty. For this one request a
   * follower's answer would be flatly wrong rather than merely behind.
   */
  record GetNodeHeartbeat(String nodeId) implements Request {}

  /**
   * Leader-routed for the identical reason {@link GetNodeHeartbeat} is: it reports since when
   * *this* leader has been collecting heartbeats, which is what lets a caller read an absent
   * heartbeat as "not heard yet" rather than "node is dead" for the first moments after leadership
   * moves.
   */
  record GetNodeObservationWindow() implements Request {}

  /**
   * A full-state snapshot, taken from the current leader specifically so a caller backing up the
   * cluster gets a point-in-time view that's never stale by a not-yet-caught-up follower's replay
   * lag. Answered by encoding {@code StateStore#snapshot()} via {@code RaftCodec#encodeSnapshot};
   * restoring one back goes through the ordinary replicated {@link Propose} path instead (as a
   * {@code StateMutation.RestoreSnapshot}), not a dedicated request here, so every replica applies
   * it the same way any other mutation is applied rather than one node's local state silently
   * diverging from the rest of the cluster.
   */
  record GetSnapshot() implements Request {}

  // ---- reads: served by the leader, against a read index it establishes first ----

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

  /**
   * Empty means the rule has never crossed or resolved yet -- see {@code
   * StateStore#putAlertFiringState}'s own javadoc for the absent/true/false three-state meaning.
   */
  record GetAlertFiringState(Optional<String> tenantId, String name) implements Request {}

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
   * Response reuses {@link BoolResult} -- same shape as {@link IsCertificateRevoked}'s, a different
   * kind of compromised credential checked the identical way.
   */
  record IsSecretsKeyRetired(byte keyId) implements Request {}

  /**
   * Response reuses {@link StringSetResult} -- each retired key id as its decimal string, the same
   * "small integer set as strings" shape {@link ListRevokedCertificateSerials} already needs no
   * dedicated result type for.
   */
  record ListRetiredSecretsKeyIds() implements Request {}

  /**
   * Response reuses {@link GenerationResult} -- same shape as {@link GetDeploymentGeneration}'s.
   */
  record GetSessionRevokedBeforeEpochMilli(String username) implements Request {}

  record GetWorkloadToken(String key) implements Request {}

  record GetRequestOutcome(String requestId) implements Request {}

  /** Response reuses {@link IntResult} -- {@code present} is always {@code true} for a count. */
  record CountRequestOutcomesBefore(long cutoffEpochMilli) implements Request {}

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

  /** Empty until the reconciler's first tick -- see {@code StateStore#daemonSetDesiredCounts}. */
  record GetDaemonSetDesiredCount(Optional<String> tenantId, String daemonSetName)
      implements Request {}

  record GetStatefulSetSpec(Optional<String> tenantId, String name) implements Request {}

  record ListStatefulSetSpecs() implements Request {}

  record ListStatefulSetAssignments() implements Request {}

  record ListStatefulSetAssignmentsFor(Optional<String> tenantId, String statefulSetName)
      implements Request {}

  record ListRollingStatefulSetIndices(Optional<String> tenantId, String statefulSetName)
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
   * The cluster-wide counterpart to {@link ListInstanceEvents}: every instance's own timeline at
   * once rather than one, answered by {@link InstanceEventListResult} the same way. See {@code
   * StateStore#listInstanceEvents(Optional, Optional)}'s own javadoc for the filter semantics.
   */
  record ListAllInstanceEvents(Optional<String> tenantId, Optional<Long> since)
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
   * A leader-evaluated, deterministic rejection -- deliberately not folded into {@link NotLeader}:
   * retrying against the correct leader would just reject identically, unlike a genuine not-leader
   * redirect, which retrying elsewhere resolves. Covers two distinct call sites, each surfacing
   * this differently on the client: {@link Propose} rejecting a {@link
   * StateMutation.PutDeployment}/ {@link StateMutation.RemoveDeployment}'s own generation
   * precondition ({@code StoreClient} surfaces this as a real, expected {@code
   * MutationOutcome.Rejected} value, not an exception), and {@link AddServer}/{@link RemoveServer}
   * rejecting an already-a-member/not-a-member/ change-still-in-flight request from a node that
   * genuinely is the current leader ({@code StoreClient} throws {@code
   * GimleRaftException#membershipChangeRejected} carrying {@code reason} verbatim) -- as opposed to
   * a node that answers this way because it merely isn't the leader, which still gets a real {@link
   * NotLeader} redirect.
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

  record RequestOutcomeResult(boolean present, RequestOutcomeRecord value) implements Response {}

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

  /**
   * {@code present == false} means the rule has never crossed or resolved since it (or a same-named
   * predecessor) was created -- {@code firing} is meaningless ({@code false}) in that case, the
   * same "meaningless placeholder" convention {@link IntResult} already uses.
   */
  record AlertFiringStateResult(boolean present, boolean firing) implements Response {}

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
