package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.MutationOutcome;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client side of {@link StoreRpc}: what {@code ApiServer}, the reconcilers, and {@code
 * Authorizer} talk to in place of a direct {@code StateStore} reference, replacing what used to be
 * the module's own in-process {@code StateStore}/{@code RaftNode} fields. Deliberately mirrors
 * every {@code StateStore} read method's name and signature exactly -- a call site that used to
 * read {@code store.listDeployments()} still reads {@code storeClient.listDeployments()} -- and
 * implements {@link MutationSink} so it drops straight into every reconciler's existing constructor
 * parameter with no other code change.
 *
 * <p>Reads go to any configured endpoint, rotating on transport failure -- no leader-awareness,
 * matching how a follower's own co-located {@code StateStore} could already be slightly stale
 * today. {@link #propose}, {@link #putHeartbeat}, {@link #tryAcquireOrRenewLease}, and {@link
 * #releaseLease} are leader-only: on {@link StoreRpc.NotLeader}, this client follows the returned
 * address and retries once rather than a {@code StoreNode} silently forwarding the write itself,
 * then caches the successful endpoint as the preferred leader for next time.
 */
public final class StoreClient implements MutationSink, StoreReader, AutoCloseable {

  private final List<SocketAddress> endpoints;
  private final Map<SocketAddress, StoreConnection> connections = new ConcurrentHashMap<>();
  private final AtomicInteger readCursor = new AtomicInteger();
  private final AtomicReference<SocketAddress> preferredLeader = new AtomicReference<>();

  public StoreClient(List<SocketAddress> endpoints) {
    if (endpoints.isEmpty()) {
      throw new IllegalArgumentException("StoreClient requires at least one store endpoint");
    }
    this.endpoints = List.copyOf(endpoints);
  }

  // ---- MutationSink / leader-only writes ----

  /**
   * {@code MutationRejected} answers a CAS-guarded mutation's own precondition failure -- a normal,
   * expected {@link MutationOutcome.Rejected} value, not folded into this method's usual {@link
   * com.gimle.core.exception.GimleRaftException#storeUnreachable} failure path the way {@link
   * StoreRpc.NotLeader} redirects already are.
   */
  @Override
  public MutationOutcome propose(StateMutation mutation) {
    StoreRpc.Response response = sendLeaderOnly("propose", new StoreRpc.Propose(mutation));
    return response instanceof StoreRpc.MutationRejected rejected
        ? MutationOutcome.rejected(rejected.reason())
        : MutationOutcome.accepted();
  }

  public void putHeartbeat(NodeHeartbeat heartbeat) {
    sendLeaderOnly("putHeartbeat", new StoreRpc.PutHeartbeat(heartbeat));
  }

  public LeaseGrant tryAcquireOrRenewLease(String name, String holderId, Duration ttl) {
    StoreRpc.LeaseResult result =
        (StoreRpc.LeaseResult)
            sendLeaderOnly(
                "tryAcquireOrRenewLease",
                new StoreRpc.AcquireOrRenewLease(name, holderId, ttl.toMillis()));
    return new LeaseGrant(
        result.granted(), result.holderId(), Instant.ofEpochMilli(result.expiresAtEpochMilli()));
  }

  public void releaseLease(String name, String holderId) {
    sendLeaderOnly("releaseLease", new StoreRpc.ReleaseLease(name, holderId));
  }

  /**
   * Adds {@code peerId} to the cluster's Raft membership -- etcd-style, one server at a time.
   * Leader-only, same redirect-and-retry posture as {@link #propose}: a rejection for any reason
   * (already a member, another change still in flight, or a genuine non-leader) surfaces as {@link
   * com.gimle.core.exception.GimleRaftException#storeUnreachable} once every endpoint -- including
   * the leader hint -- has been tried, matching {@link #sendLeaderOnly}'s existing behavior for
   * every other leader-only write.
   */
  public void addServer(String peerId, PeerAddress address) {
    sendLeaderOnly(
        "addServer",
        new StoreRpc.AddServer(peerId, address.host(), address.raftPort(), address.clientPort()));
  }

  /** The symmetric removal counterpart to {@link #addServer}. */
  public void removeServer(String peerId) {
    sendLeaderOnly("removeServer", new StoreRpc.RemoveServer(peerId));
  }

  /**
   * Leader-routed, unlike every other read below: node heartbeats are deliberately never replicated
   * through the Raft log, so a follower's local copy is never anything but empty -- round-robining
   * this the way every other read here does would silently answer "no heartbeat" from a replica
   * that never held leadership, forever, not just return a stale-but-eventually- correct answer.
   * Reuses {@link #sendLeaderOnly}'s existing preferred-leader cache and {@code
   * NotLeader}-hint-follow machinery; callers already handle a {@link
   * com.gimle.core.exception.GimleRaftException#storeUnreachable} from every other leader-only call
   * on this client (a reconciler tick's own {@code propose} can already throw the same way during a
   * leader-election gap), so this needs no new caller-side handling.
   */
  public Optional<ObservedHeartbeat> getNodeHeartbeat(String nodeId) {
    StoreRpc.HeartbeatResult r =
        (StoreRpc.HeartbeatResult)
            sendLeaderOnly("getNodeHeartbeat", new StoreRpc.GetNodeHeartbeat(nodeId));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  // ---- reads: same names/signatures as StateStore ----

  public List<Account> listAccounts() {
    return ((StoreRpc.AccountListResult) sendRead(new StoreRpc.ListAccounts())).values();
  }

  public Optional<Tenant> getTenant(String id) {
    StoreRpc.TenantResult r = (StoreRpc.TenantResult) sendRead(new StoreRpc.GetTenant(id));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<DeploymentSpec> getDeployment(Optional<String> tenantId, String name) {
    StoreRpc.DeploymentResult r =
        (StoreRpc.DeploymentResult) sendRead(new StoreRpc.GetDeployment(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  /**
   * Any-node-servable, unlike {@link #propose}: a stale answer here only ever causes a spurious,
   * safe rejection at the actual CAS check inside {@code StateMutation.PutDeployment}/{@code
   * RemoveDeployment}'s own {@code applyTo} -- evaluated against the true replicated state at apply
   * time, never against this read -- so it can never cause an incorrect write, only an occasional
   * unnecessary conflict response a caller can retry.
   */
  @Override
  public long getDeploymentGeneration(Optional<String> tenantId, String name) {
    return ((StoreRpc.GenerationResult)
            sendRead(new StoreRpc.GetDeploymentGeneration(tenantId, name)))
        .value();
  }

  public List<DeploymentSpec> listDeployments() {
    return ((StoreRpc.DeploymentListResult) sendRead(new StoreRpc.ListDeployments())).values();
  }

  public Optional<ServiceSpec> getService(Optional<String> tenantId, String name) {
    StoreRpc.ServiceResult r =
        (StoreRpc.ServiceResult) sendRead(new StoreRpc.GetService(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<ServiceSpec> listServices() {
    return ((StoreRpc.ServiceListResult) sendRead(new StoreRpc.ListServices())).values();
  }

  public Optional<NetworkPolicySpec> getNetworkPolicy(String tenantId, String name) {
    StoreRpc.NetworkPolicyResult r =
        (StoreRpc.NetworkPolicyResult) sendRead(new StoreRpc.GetNetworkPolicy(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<NetworkPolicySpec> listNetworkPolicies() {
    return ((StoreRpc.NetworkPolicyListResult) sendRead(new StoreRpc.ListNetworkPolicies()))
        .values();
  }

  public Optional<LimitRangeSpec> getLimitRange(String tenantId) {
    StoreRpc.LimitRangeResult r =
        (StoreRpc.LimitRangeResult) sendRead(new StoreRpc.GetLimitRange(tenantId));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<LimitRangeSpec> listLimitRanges() {
    return ((StoreRpc.LimitRangeListResult) sendRead(new StoreRpc.ListLimitRanges())).values();
  }

  @Override
  public List<KindDefinitionSpec> listKindDefinitions() {
    return ((StoreRpc.KindDefinitionListResult) sendRead(new StoreRpc.ListKindDefinitions()))
        .values();
  }

  @Override
  public Optional<KindDefinitionSpec> getKindDefinition(String kindName) {
    StoreRpc.KindDefinitionResult r =
        (StoreRpc.KindDefinitionResult) sendRead(new StoreRpc.GetKindDefinition(kindName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  @Override
  public List<CustomResource> listCustomResources(String kindName) {
    return ((StoreRpc.CustomResourceListResult)
            sendRead(new StoreRpc.ListCustomResources(kindName)))
        .values();
  }

  @Override
  public List<CustomResource> listCustomResourcesFor(String kindName, Optional<String> tenantId) {
    return ((StoreRpc.CustomResourceListResult)
            sendRead(new StoreRpc.ListCustomResourcesFor(kindName, tenantId)))
        .values();
  }

  @Override
  public Optional<CustomResource> getCustomResource(
      String kindName, Optional<String> tenantId, String name) {
    StoreRpc.CustomResourceResult r =
        (StoreRpc.CustomResourceResult)
            sendRead(new StoreRpc.GetCustomResource(kindName, tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<InstanceAssignment> listAssignmentsFor(
      Optional<String> tenantId, String deploymentName) {
    return ((StoreRpc.AssignmentListResult)
            sendRead(new StoreRpc.ListAssignmentsFor(tenantId, deploymentName)))
        .values();
  }

  public boolean isQuotaViolating(Optional<String> tenantId, String deploymentName) {
    return ((StoreRpc.BoolResult) sendRead(new StoreRpc.IsQuotaViolating(tenantId, deploymentName)))
        .value();
  }

  public boolean isLimitRangeViolating(Optional<String> tenantId, String deploymentName) {
    return ((StoreRpc.BoolResult)
            sendRead(new StoreRpc.IsLimitRangeViolating(tenantId, deploymentName)))
        .value();
  }

  public Optional<String> limitRangeViolationReason(
      Optional<String> tenantId, String deploymentName) {
    StoreRpc.StringResult r =
        (StoreRpc.StringResult)
            sendRead(new StoreRpc.GetLimitRangeViolationReason(tenantId, deploymentName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public boolean isNodeCordoned(String nodeId) {
    return ((StoreRpc.BoolResult) sendRead(new StoreRpc.IsNodeCordoned(nodeId))).value();
  }

  @Override
  public Set<String> getNodeTaints(String nodeId) {
    return Set.copyOf(
        ((StoreRpc.StringSetResult) sendRead(new StoreRpc.GetNodeTaints(nodeId))).values());
  }

  @Override
  public boolean isCertificateRevoked(String serialNumber) {
    return ((StoreRpc.BoolResult) sendRead(new StoreRpc.IsCertificateRevoked(serialNumber)))
        .value();
  }

  @Override
  public Optional<WorkloadTokenRecord> getWorkloadToken(String key) {
    StoreRpc.WorkloadTokenResult result =
        (StoreRpc.WorkloadTokenResult) sendRead(new StoreRpc.GetWorkloadToken(key));
    return result.present() ? Optional.of(result.value()) : Optional.empty();
  }

  @Override
  public Set<String> listRevokedCertificateSerials() {
    return Set.copyOf(
        ((StoreRpc.StringSetResult) sendRead(new StoreRpc.ListRevokedCertificateSerials()))
            .values());
  }

  public List<InstanceAssignment> listAssignments() {
    return ((StoreRpc.AssignmentListResult) sendRead(new StoreRpc.ListAssignments())).values();
  }

  public Optional<JobSpec> getJobSpec(Optional<String> tenantId, String name) {
    StoreRpc.JobSpecResult r =
        (StoreRpc.JobSpecResult) sendRead(new StoreRpc.GetJobSpec(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<JobSpec> listJobSpecs() {
    return ((StoreRpc.JobSpecListResult) sendRead(new StoreRpc.ListJobSpecs())).values();
  }

  public List<JobRun> listJobRunsFor(Optional<String> tenantId, String jobName) {
    return ((StoreRpc.JobRunListResult) sendRead(new StoreRpc.ListJobRunsFor(tenantId, jobName)))
        .values();
  }

  public List<JobRun> listJobRuns() {
    return ((StoreRpc.JobRunListResult) sendRead(new StoreRpc.ListJobRuns())).values();
  }

  public Optional<JobPhase> getJobPhase(Optional<String> tenantId, String jobName) {
    StoreRpc.JobPhaseResult r =
        (StoreRpc.JobPhaseResult) sendRead(new StoreRpc.GetJobPhase(tenantId, jobName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<JobRunSummary> getJobRunSummary(Optional<String> tenantId, String jobName) {
    StoreRpc.JobRunSummaryResult r =
        (StoreRpc.JobRunSummaryResult) sendRead(new StoreRpc.GetJobRunSummary(tenantId, jobName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<CronJobSpec> getCronJobSpec(Optional<String> tenantId, String name) {
    StoreRpc.CronJobSpecResult r =
        (StoreRpc.CronJobSpecResult) sendRead(new StoreRpc.GetCronJobSpec(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<CronJobSpec> listCronJobSpecs() {
    return ((StoreRpc.CronJobSpecListResult) sendRead(new StoreRpc.ListCronJobSpecs())).values();
  }

  public Optional<Instant> getCronJobLastSchedule(Optional<String> tenantId, String name) {
    StoreRpc.InstantResult r =
        (StoreRpc.InstantResult) sendRead(new StoreRpc.GetCronJobLastSchedule(tenantId, name));
    return r.present() ? Optional.of(Instant.ofEpochMilli(r.epochMilli())) : Optional.empty();
  }

  public Optional<DaemonSetSpec> getDaemonSetSpec(Optional<String> tenantId, String name) {
    StoreRpc.DaemonSetSpecResult r =
        (StoreRpc.DaemonSetSpecResult) sendRead(new StoreRpc.GetDaemonSetSpec(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<DaemonSetSpec> listDaemonSetSpecs() {
    return ((StoreRpc.DaemonSetSpecListResult) sendRead(new StoreRpc.ListDaemonSetSpecs()))
        .values();
  }

  public List<DaemonSetAssignment> listDaemonSetAssignments() {
    return ((StoreRpc.DaemonSetAssignmentListResult)
            sendRead(new StoreRpc.ListDaemonSetAssignments()))
        .values();
  }

  public List<DaemonSetAssignment> listDaemonSetAssignmentsFor(
      Optional<String> tenantId, String daemonSetName) {
    return ((StoreRpc.DaemonSetAssignmentListResult)
            sendRead(new StoreRpc.ListDaemonSetAssignmentsFor(tenantId, daemonSetName)))
        .values();
  }

  public Set<String> getRollingDaemonSetNodes(Optional<String> tenantId, String daemonSetName) {
    return Set.copyOf(
        ((StoreRpc.StringSetResult)
                sendRead(new StoreRpc.ListRollingDaemonSetNodes(tenantId, daemonSetName)))
            .values());
  }

  public Optional<StatefulSetSpec> getStatefulSetSpec(Optional<String> tenantId, String name) {
    StoreRpc.StatefulSetSpecResult r =
        (StoreRpc.StatefulSetSpecResult) sendRead(new StoreRpc.GetStatefulSetSpec(tenantId, name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<StatefulSetSpec> listStatefulSetSpecs() {
    return ((StoreRpc.StatefulSetSpecListResult) sendRead(new StoreRpc.ListStatefulSetSpecs()))
        .values();
  }

  public List<StatefulSetAssignment> listStatefulSetAssignments() {
    return ((StoreRpc.StatefulSetAssignmentListResult)
            sendRead(new StoreRpc.ListStatefulSetAssignments()))
        .values();
  }

  public List<StatefulSetAssignment> listStatefulSetAssignmentsFor(
      Optional<String> tenantId, String statefulSetName) {
    return ((StoreRpc.StatefulSetAssignmentListResult)
            sendRead(new StoreRpc.ListStatefulSetAssignmentsFor(tenantId, statefulSetName)))
        .values();
  }

  public Optional<Integer> getRollingStatefulSetIndex(
      Optional<String> tenantId, String statefulSetName) {
    StoreRpc.IntResult r =
        (StoreRpc.IntResult)
            sendRead(new StoreRpc.GetRollingStatefulSetIndex(tenantId, statefulSetName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<String> getStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    StoreRpc.StringResult r =
        (StoreRpc.StringResult)
            sendRead(
                new StoreRpc.GetStatefulSetIndexNode(tenantId, statefulSetName, instanceIndex));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<NodeRegistration> listNodeRegistrations() {
    return ((StoreRpc.NodeRegistrationListResult) sendRead(new StoreRpc.ListNodeRegistrations()))
        .values();
  }

  public List<Tenant> listTenants() {
    return ((StoreRpc.TenantListResult) sendRead(new StoreRpc.ListTenants())).values();
  }

  /**
   * The answering node's own view of the cluster (Raft id, leadership, leader hint, membership) --
   * served by whichever configured endpoint answers first, like every other read here. A follower's
   * view names the leader too, so any answering node identifies it; {@code leaderId()} is {@code
   * ""} in a mid-election gap.
   */
  public StoreRpc.StatusResult status() {
    return (StoreRpc.StatusResult) sendRead(new StoreRpc.Status());
  }

  public List<ConfigEntry> listConfigEntriesFor(String tenantId) {
    return ((StoreRpc.ConfigEntryListResult) sendRead(new StoreRpc.ListConfigEntriesFor(tenantId)))
        .values();
  }

  /**
   * Same query as {@link #listConfigEntriesFor}, but routed only to the current leader -- see
   * {@link StoreRpc.ListConfigEntriesForLinearizable}'s own javadoc for why a caller like {@code
   * SecretStore.put}'s own before/after version check needs this instead of the round-robin
   * default.
   */
  public List<ConfigEntry> listConfigEntriesForLinearizable(String tenantId) {
    return ((StoreRpc.ConfigEntryListResult)
            sendLeaderOnly(
                "listConfigEntriesForLinearizable",
                new StoreRpc.ListConfigEntriesForLinearizable(tenantId)))
        .values();
  }

  public List<Role> listRoles() {
    return ((StoreRpc.RoleListResult) sendRead(new StoreRpc.ListRoles())).values();
  }

  public Optional<Role> getRole(String name) {
    StoreRpc.RoleResult r = (StoreRpc.RoleResult) sendRead(new StoreRpc.GetRole(name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<RoleBinding> listRoleBindings() {
    return ((StoreRpc.RoleBindingListResult) sendRead(new StoreRpc.ListRoleBindings())).values();
  }

  public Optional<RoleBinding> getRoleBinding(String id) {
    StoreRpc.RoleBindingResult r =
        (StoreRpc.RoleBindingResult) sendRead(new StoreRpc.GetRoleBinding(id));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<Account> getAccount(String username) {
    StoreRpc.AccountResult r = (StoreRpc.AccountResult) sendRead(new StoreRpc.GetAccount(username));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<NodeRegistration> getNodeRegistration(String nodeId) {
    StoreRpc.NodeRegistrationResult r =
        (StoreRpc.NodeRegistrationResult) sendRead(new StoreRpc.GetNodeRegistration(nodeId));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<Integer> getEffectiveReplicas(Optional<String> tenantId, String deploymentName) {
    StoreRpc.IntResult r =
        (StoreRpc.IntResult) sendRead(new StoreRpc.GetEffectiveReplicas(tenantId, deploymentName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Set<Integer> getRollingIndices(Optional<String> tenantId, String deploymentName) {
    return Set.copyOf(
        ((StoreRpc.IntSetResult)
                sendRead(new StoreRpc.ListRollingIndices(tenantId, deploymentName)))
            .values());
  }

  public Map<Integer, Integer> getSurgeIndices(Optional<String> tenantId, String deploymentName) {
    StoreRpc.IntIntMapResult r =
        (StoreRpc.IntIntMapResult)
            sendRead(new StoreRpc.ListSurgeIndices(tenantId, deploymentName));
    Map<Integer, Integer> result = new LinkedHashMap<>();
    for (int i = 0; i < r.surgeIndices().size(); i++) {
      result.put(r.surgeIndices().get(i), r.targetIndices().get(i));
    }
    return Map.copyOf(result);
  }

  public Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    StoreRpc.ReconcilerInstanceStateResult r =
        (StoreRpc.ReconcilerInstanceStateResult)
            sendRead(
                new StoreRpc.GetReconcilerInstanceState(tenantId, deploymentName, instanceIndex));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<ReconcilerInstanceState> listReconcilerInstanceStates() {
    return ((StoreRpc.ReconcilerInstanceStateListResult)
            sendRead(new StoreRpc.ListReconcilerInstanceStates()))
        .values();
  }

  public List<InstanceEvent> listInstanceEvents(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return ((StoreRpc.InstanceEventListResult)
            sendRead(new StoreRpc.ListInstanceEvents(tenantId, deploymentName, instanceIndex)))
        .values();
  }

  public List<AuditEvent> listAuditEvents(
      Optional<String> principal,
      Optional<String> resourceKind,
      Optional<String> tenantId,
      Optional<Long> since) {
    return ((StoreRpc.AuditEventListResult)
            sendRead(new StoreRpc.ListAuditEvents(principal, resourceKind, tenantId, since)))
        .values();
  }

  public List<ControllerRevision> listControllerRevisions(
      String workloadKind, Optional<String> tenantId, String name) {
    return ((StoreRpc.ControllerRevisionListResult)
            sendRead(new StoreRpc.ListControllerRevisions(workloadKind, tenantId, name)))
        .values();
  }

  public Optional<ControllerRevision> getControllerRevision(
      String workloadKind, Optional<String> tenantId, String name, int revision) {
    StoreRpc.ControllerRevisionResult r =
        (StoreRpc.ControllerRevisionResult)
            sendRead(new StoreRpc.GetControllerRevision(workloadKind, tenantId, name, revision));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  // ---- transport ----

  private StoreConnection connectionFor(SocketAddress address) {
    return connections.computeIfAbsent(address, StoreConnection::new);
  }

  /**
   * Tries every configured endpoint in rotation, starting from a shared cursor so repeated calls
   * spread across the pool rather than pinning to one -- reads never need leader-awareness, only
   * tolerance of one endpoint being unreachable.
   */
  private StoreRpc.Response sendRead(StoreRpc.Request request) {
    int start = readCursor.getAndUpdate(i -> (i + 1) % endpoints.size());
    for (int i = 0; i < endpoints.size(); i++) {
      SocketAddress address = endpoints.get((start + i) % endpoints.size());
      try {
        return connectionFor(address).call(request);
      } catch (UncheckedIOException e) {
        // this endpoint is unreachable this attempt; the next one in rotation may still answer.
        // A gray failure (reachable but silent) surfaces here the same way as a clean refusal --
        // StoreConnection's own connect/read timeouts turn it into a SocketTimeoutException,
        // which is still an IOException, so it needs no special case beyond this one.
      }
    }
    throw GimleRaftException.storeUnreachable(request.getClass().getSimpleName());
  }

  /**
   * Tries the cached preferred leader first (if any), then every configured endpoint; on {@link
   * StoreRpc.NotLeader} with a non-blank hint, makes one direct follow-up attempt against that
   * exact address before moving on. The first endpoint to answer with anything other than {@code
   * NotLeader} becomes the new cached preferred leader.
   */
  private StoreRpc.Response sendLeaderOnly(String operationName, StoreRpc.Request request) {
    SocketAddress cached = preferredLeader.get();
    if (cached != null) {
      StoreRpc.Response response = tryOnce(cached, request);
      if (response != null) {
        if (!(response instanceof StoreRpc.NotLeader notLeader)) {
          return response;
        }
        StoreRpc.Response followed = followLeaderHint(notLeader, request);
        if (followed != null) {
          return followed;
        }
      }
    }
    for (SocketAddress address : endpoints) {
      if (address.equals(cached)) {
        continue; // already tried above
      }
      StoreRpc.Response response = tryOnce(address, request);
      if (response == null) {
        continue;
      }
      if (!(response instanceof StoreRpc.NotLeader notLeader)) {
        preferredLeader.set(address);
        return response;
      }
      StoreRpc.Response followed = followLeaderHint(notLeader, request);
      if (followed != null) {
        return followed;
      }
    }
    throw GimleRaftException.storeUnreachable(operationName);
  }

  /** One direct retry against a {@link StoreRpc.NotLeader} hint's address. */
  private StoreRpc.Response followLeaderHint(
      StoreRpc.NotLeader notLeader, StoreRpc.Request request) {
    if (notLeader.leaderClientAddress().isBlank()) {
      return null;
    }
    SocketAddress hinted = parseAddress(notLeader.leaderClientAddress());
    StoreRpc.Response response = tryOnce(hinted, request);
    if (response != null && !(response instanceof StoreRpc.NotLeader)) {
      preferredLeader.set(hinted);
      return response;
    }
    return null;
  }

  /**
   * Returns {@code null} (never throws) on transport failure, so callers can just try the next
   * endpoint.
   */
  private StoreRpc.Response tryOnce(SocketAddress address, StoreRpc.Request request) {
    try {
      return connectionFor(address).call(request);
    } catch (UncheckedIOException e) {
      return null;
    }
  }

  private static SocketAddress parseAddress(String hostPort) {
    int colon = hostPort.lastIndexOf(':');
    String host = hostPort.substring(0, colon);
    int port = Integer.parseInt(hostPort.substring(colon + 1));
    return new InetSocketAddress(host, port);
  }

  @Override
  public void close() {
    for (StoreConnection connection : connections.values()) {
      connection.close();
    }
  }
}
