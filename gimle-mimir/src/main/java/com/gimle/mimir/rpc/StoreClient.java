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
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.PeerAddress;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.LeaseGrant;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StoreReader;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client side of {@link StoreRpc}: what {@code ApiServer}, the reconcilers, and {@code
 * Authorizer} talk to in place of a direct {@code StateStore} reference (etcd-store-extraction
 * design doc, replacing the module's former in-process {@code StateStore}/{@code RaftNode} fields).
 * Deliberately mirrors every {@code StateStore} read method's name and signature exactly -- a call
 * site that used to read {@code store.listDeployments()} still reads {@code
 * storeClient.listDeployments()} -- and implements {@link MutationSink} so it drops straight into
 * every reconciler's existing constructor parameter with no other code change.
 *
 * <p>Reads (design doc §4.5) go to any configured endpoint, rotating on transport failure -- no
 * leader-awareness, matching how a follower's own co-located {@code StateStore} could already be
 * slightly stale today. {@link #propose}, {@link #putHeartbeat}, {@link #tryAcquireOrRenewLease},
 * and {@link #releaseLease} are leader-only: on {@link StoreRpc.NotLeader}, this client follows the
 * returned address and retries once (§4.4/§4.6) rather than a {@code StoreNode} silently forwarding
 * the write itself, then caches the successful endpoint as the preferred leader for next time.
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

  @Override
  public void propose(StateMutation mutation) {
    sendLeaderOnly("propose", new StoreRpc.Propose(mutation));
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
   * Adds {@code peerId} to the cluster's Raft membership -- etcd-style, one server at a time
   * (P1-5). Leader-only, same redirect-and-retry posture as {@link #propose}: a rejection for any
   * reason (already a member, another change still in flight, or a genuine non-leader) surfaces as
   * {@link com.gimle.core.exception.GimleRaftException#storeUnreachable} once every endpoint --
   * including the leader hint -- has been tried, matching {@link #sendLeaderOnly}'s existing
   * behavior for every other leader-only write.
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
   * Leader-routed, unlike every other read below (P2-14): node heartbeats are deliberately never
   * replicated through the Raft log, so a follower's local copy is never anything but empty --
   * round-robining this the way every other read here does would silently answer "no heartbeat"
   * from a replica that never held leadership, forever, not just return a stale-but-eventually-
   * correct answer. Reuses {@link #sendLeaderOnly}'s existing preferred-leader cache and {@code
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

  public Optional<DeploymentSpec> getDeployment(String name) {
    StoreRpc.DeploymentResult r =
        (StoreRpc.DeploymentResult) sendRead(new StoreRpc.GetDeployment(name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<DeploymentSpec> listDeployments() {
    return ((StoreRpc.DeploymentListResult) sendRead(new StoreRpc.ListDeployments())).values();
  }

  public List<InstanceAssignment> listAssignmentsFor(String deploymentName) {
    return ((StoreRpc.AssignmentListResult)
            sendRead(new StoreRpc.ListAssignmentsFor(deploymentName)))
        .values();
  }

  public boolean isQuotaViolating(String deploymentName) {
    return ((StoreRpc.BoolResult) sendRead(new StoreRpc.IsQuotaViolating(deploymentName))).value();
  }

  public boolean isNodeCordoned(String nodeId) {
    return ((StoreRpc.BoolResult) sendRead(new StoreRpc.IsNodeCordoned(nodeId))).value();
  }

  public List<InstanceAssignment> listAssignments() {
    return ((StoreRpc.AssignmentListResult) sendRead(new StoreRpc.ListAssignments())).values();
  }

  public Optional<JobSpec> getJobSpec(String name) {
    StoreRpc.JobSpecResult r = (StoreRpc.JobSpecResult) sendRead(new StoreRpc.GetJobSpec(name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<JobSpec> listJobSpecs() {
    return ((StoreRpc.JobSpecListResult) sendRead(new StoreRpc.ListJobSpecs())).values();
  }

  public List<JobRun> listJobRunsFor(String jobName) {
    return ((StoreRpc.JobRunListResult) sendRead(new StoreRpc.ListJobRunsFor(jobName))).values();
  }

  public List<JobRun> listJobRuns() {
    return ((StoreRpc.JobRunListResult) sendRead(new StoreRpc.ListJobRuns())).values();
  }

  public Optional<JobPhase> getJobPhase(String jobName) {
    StoreRpc.JobPhaseResult r =
        (StoreRpc.JobPhaseResult) sendRead(new StoreRpc.GetJobPhase(jobName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<CronJobSpec> getCronJobSpec(String name) {
    StoreRpc.CronJobSpecResult r =
        (StoreRpc.CronJobSpecResult) sendRead(new StoreRpc.GetCronJobSpec(name));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<CronJobSpec> listCronJobSpecs() {
    return ((StoreRpc.CronJobSpecListResult) sendRead(new StoreRpc.ListCronJobSpecs())).values();
  }

  public Optional<Instant> getCronJobLastSchedule(String name) {
    StoreRpc.InstantResult r =
        (StoreRpc.InstantResult) sendRead(new StoreRpc.GetCronJobLastSchedule(name));
    return r.present() ? Optional.of(Instant.ofEpochMilli(r.epochMilli())) : Optional.empty();
  }

  public List<NodeRegistration> listNodeRegistrations() {
    return ((StoreRpc.NodeRegistrationListResult) sendRead(new StoreRpc.ListNodeRegistrations()))
        .values();
  }

  public List<Tenant> listTenants() {
    return ((StoreRpc.TenantListResult) sendRead(new StoreRpc.ListTenants())).values();
  }

  public List<ConfigEntry> listConfigEntriesFor(String tenantId) {
    return ((StoreRpc.ConfigEntryListResult) sendRead(new StoreRpc.ListConfigEntriesFor(tenantId)))
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

  public Optional<Integer> getEffectiveReplicas(String deploymentName) {
    StoreRpc.IntResult r =
        (StoreRpc.IntResult) sendRead(new StoreRpc.GetEffectiveReplicas(deploymentName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<Integer> getRollingIndex(String deploymentName) {
    StoreRpc.IntResult r =
        (StoreRpc.IntResult) sendRead(new StoreRpc.GetRollingIndex(deploymentName));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      String deploymentName, int instanceIndex) {
    StoreRpc.ReconcilerInstanceStateResult r =
        (StoreRpc.ReconcilerInstanceStateResult)
            sendRead(new StoreRpc.GetReconcilerInstanceState(deploymentName, instanceIndex));
    return r.present() ? Optional.of(r.value()) : Optional.empty();
  }

  public List<ReconcilerInstanceState> listReconcilerInstanceStates() {
    return ((StoreRpc.ReconcilerInstanceStateListResult)
            sendRead(new StoreRpc.ListReconcilerInstanceStates()))
        .values();
  }

  public List<InstanceEvent> listInstanceEvents(String deploymentName, int instanceIndex) {
    return ((StoreRpc.InstanceEventListResult)
            sendRead(new StoreRpc.ListInstanceEvents(deploymentName, instanceIndex)))
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

  // ---- transport ----

  private StoreConnection connectionFor(SocketAddress address) {
    return connections.computeIfAbsent(address, StoreConnection::new);
  }

  /**
   * Tries every configured endpoint in rotation, starting from a shared cursor so repeated calls
   * spread across the pool rather than pinning to one -- reads never need leader-awareness (design
   * doc §4.5), only tolerance of one endpoint being unreachable.
   */
  private StoreRpc.Response sendRead(StoreRpc.Request request) {
    int start = readCursor.getAndUpdate(i -> (i + 1) % endpoints.size());
    for (int i = 0; i < endpoints.size(); i++) {
      SocketAddress address = endpoints.get((start + i) % endpoints.size());
      try {
        return connectionFor(address).call(request);
      } catch (UncheckedIOException e) {
        // this endpoint is unreachable this attempt; the next one in rotation may still answer.
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

  /** One direct retry against a {@link StoreRpc.NotLeader} hint's address, per §4.4/§4.6. */
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
    return new java.net.InetSocketAddress(host, port);
  }

  @Override
  public void close() {
    for (StoreConnection connection : connections.values()) {
      connection.close();
    }
  }
}
