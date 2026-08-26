package com.gimle.mimir.raft;

import com.gimle.core.authz.Account;
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
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.time.Instant;
import java.util.List;

/**
 * Every mutating operation {@link StateStore} exposes, replicated through the Raft log -- one
 * variant per {@code StateStore} method that changes durable state, applied to the store via {@link
 * #applyTo} once a {@link RaftNode} commits the entry. {@code putNodeHeartbeat} deliberately has no
 * variant here: heartbeats are high-frequency, tolerate a brief gap after a leader change, and
 * would make the log's write rate scale with cluster size for no correctness benefit -- only the
 * leader's own {@code StateStore} ever receives them, outside the log entirely.
 */
public sealed interface StateMutation extends RaftLogPayload {

  void applyTo(StateStore store);

  record PutDeployment(DeploymentSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putDeployment(spec);
    }
  }

  record RemoveDeployment(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeDeployment(name);
    }
  }

  record PutService(ServiceSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putService(spec);
    }
  }

  record RemoveService(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeService(name);
    }
  }

  record PutNetworkPolicy(NetworkPolicySpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putNetworkPolicy(spec);
    }
  }

  record RemoveNetworkPolicy(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeNetworkPolicy(name);
    }
  }

  record PutAssignment(InstanceAssignment assignment) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putAssignment(assignment);
    }
  }

  record RemoveAssignment(String deploymentName, int instanceIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeAssignment(deploymentName, instanceIndex);
    }
  }

  record PutJobSpec(JobSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putJobSpec(spec);
    }
  }

  record RemoveJobSpec(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeJobSpec(name);
    }
  }

  record PutJobRun(JobRun run) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putJobRun(run);
    }
  }

  record RemoveJobRun(String jobName, int attempt) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeJobRun(jobName, attempt);
    }
  }

  record PutJobPhase(String jobName, JobPhase phase) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putJobPhase(jobName, phase);
    }
  }

  record PutCronJobSpec(CronJobSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putCronJobSpec(spec);
    }
  }

  record RemoveCronJobSpec(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeCronJobSpec(name);
    }
  }

  record PutCronJobLastSchedule(String name, Instant lastScheduleTime) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putCronJobLastSchedule(name, lastScheduleTime);
    }
  }

  record PutDaemonSetSpec(DaemonSetSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putDaemonSetSpec(spec);
    }
  }

  record RemoveDaemonSetSpec(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeDaemonSetSpec(name);
    }
  }

  record PutDaemonSetAssignment(DaemonSetAssignment assignment) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putDaemonSetAssignment(assignment);
    }
  }

  record RemoveDaemonSetAssignment(String daemonSetName, String nodeId) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeDaemonSetAssignment(daemonSetName, nodeId);
    }
  }

  record AddRollingDaemonSetNode(String daemonSetName, String nodeId) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.addRollingDaemonSetNode(daemonSetName, nodeId);
    }
  }

  record RemoveRollingDaemonSetNode(String daemonSetName, String nodeId) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRollingDaemonSetNode(daemonSetName, nodeId);
    }
  }

  record PutStatefulSetSpec(StatefulSetSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putStatefulSetSpec(spec);
    }
  }

  record RemoveStatefulSetSpec(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeStatefulSetSpec(name);
    }
  }

  record PutStatefulSetAssignment(StatefulSetAssignment assignment) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putStatefulSetAssignment(assignment);
    }
  }

  record RemoveStatefulSetAssignment(String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeStatefulSetAssignment(statefulSetName, instanceIndex);
    }
  }

  /**
   * The single "index currently in flight" marker governing StatefulSet forward progress -- reused
   * for both {@code OrderedReady} scale-up admission and rolling-update admission, the same
   * one-index-at-a-time gate {@code DeploymentReconciler}'s own {@code rollingIndex} enforces for
   * rolling updates alone. A separate map from {@code rollingIndex} (keyed by {@code
   * statefulSetName}, not {@code deploymentName}) -- the two resource kinds never share a
   * namespace.
   */
  record PutRollingStatefulSetIndex(String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRollingStatefulSetIndex(statefulSetName, instanceIndex);
    }
  }

  record ClearRollingStatefulSetIndex(String statefulSetName) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.clearRollingStatefulSetIndex(statefulSetName);
    }
  }

  /**
   * The sticky node-binding memory for one StatefulSet index: written once, the first time an index
   * is ever placed, and read back by every subsequent placement attempt for that same index --
   * including a rolling-update remove-then-replace, which would otherwise lose track of which node
   * the index's local-disk volume physically lives on. Survives an ordinary assignment removal
   * (mid-rollout, or a node going dark and this index sitting unplaced awaiting that same node's
   * return); only {@link RemoveStatefulSetIndexNode} clears it, fired solely on the two genuinely
   * permanent cases -- index scaled below the replica count, or the whole spec deleted.
   */
  record PutStatefulSetIndexNode(String statefulSetName, int instanceIndex, String nodeId)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putStatefulSetIndexNode(statefulSetName, instanceIndex, nodeId);
    }
  }

  record RemoveStatefulSetIndexNode(String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeStatefulSetIndexNode(statefulSetName, instanceIndex);
    }
  }

  record AddRollingIndex(String deploymentName, int instanceIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.addRollingIndex(deploymentName, instanceIndex);
    }
  }

  record RemoveRollingIndex(String deploymentName, int instanceIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRollingIndex(deploymentName, instanceIndex);
    }
  }

  /**
   * The surge counterpart to {@link AddRollingIndex}: marks {@code surgeIndex} (a synthetic index
   * {@code >= replicas}) as provisioning a replacement for {@code targetIndex} ahead of removing
   * the original -- see {@code DeploymentReconciler#handleSurge}.
   */
  record AddSurgeIndex(String deploymentName, int surgeIndex, int targetIndex)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.addSurgeIndex(deploymentName, surgeIndex, targetIndex);
    }
  }

  record RemoveSurgeIndex(String deploymentName, int surgeIndex) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeSurgeIndex(deploymentName, surgeIndex);
    }
  }

  record PutEffectiveReplicas(String deploymentName, int replicas) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putEffectiveReplicas(deploymentName, replicas);
    }
  }

  record PutNodeRegistration(NodeRegistration registration) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putNodeRegistration(registration);
    }
  }

  record PutTenant(Tenant tenant) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putTenant(tenant);
    }
  }

  record RemoveTenant(String id) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeTenant(id);
    }
  }

  record PutQuotaViolation(String deploymentName, boolean violating) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putQuotaViolation(deploymentName, violating);
    }
  }

  record PutNodeCordon(String nodeId, boolean cordoned) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putNodeCordon(nodeId, cordoned);
    }
  }

  /**
   * Marks (or clears) one issued certificate's serial number as revoked -- the portable revocation
   * answer for a compromised leaf: {@code ApiServer#resolvePrincipal} refuses a peer certificate
   * whose serial is on this list before any authorization runs, without CRL/OCSP infrastructure.
   * Keyed by serial rather than subject so revoking a compromised certificate never blocks a later,
   * legitimately re-issued one for the same identity.
   */
  record PutCertificateRevocation(String serialNumber, boolean revoked) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putCertificateRevocation(serialNumber, revoked);
    }
  }

  /**
   * Replaces one {@code deploymentName#nodeId} key's live workload-identity token record. {@code
   * mintedAtEpochMilli} is stamped once by the minting replica and carried in the mutation, so the
   * opportunistic expired-entry sweep it drives inside {@code StateStore#putWorkloadToken} makes
   * the identical decision on every replica at every replay -- a wall-clock read there would not.
   */
  record PutWorkloadToken(WorkloadTokenRecord record, long mintedAtEpochMilli)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putWorkloadToken(record, mintedAtEpochMilli);
    }
  }

  record RemoveWorkloadToken(String key) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeWorkloadToken(key);
    }
  }

  /**
   * No corresponding {@code RemoveInstanceEvent} -- retention-cap pruning is internal to {@link
   * StateStore#putInstanceEvent}, applied identically on every replica as this same mutation is
   * replayed, not a separate mutation of its own.
   */
  record AppendInstanceEvent(InstanceEvent event) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putInstanceEvent(event);
    }
  }

  /**
   * No corresponding {@code RemoveAuditEvent} for the same reason {@code AppendInstanceEvent} has
   * none -- retention-cap pruning is internal to {@link StateStore#putAuditEvent}, applied
   * identically on every replica as this mutation replays.
   */
  record AppendAuditEvent(AuditEvent event) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putAuditEvent(event);
    }
  }

  /**
   * No corresponding {@code RemoveControllerRevision} for the same reason {@code
   * AppendInstanceEvent}/{@code AppendAuditEvent} have none -- retention-cap pruning is internal to
   * {@link StateStore#putControllerRevision}, applied identically on every replica as this mutation
   * replays.
   */
  record AppendControllerRevision(ControllerRevision revision) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putControllerRevision(revision);
    }
  }

  record PutLimitRange(LimitRangeSpec spec) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putLimitRange(spec);
    }
  }

  record RemoveLimitRange(String tenantId) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeLimitRange(tenantId);
    }
  }

  /**
   * A blank {@code reason} clears the violation; a non-blank one sets it -- same convention {@link
   * com.gimle.mimir.store.StateStore#putLimitRangeViolation} documents on its own.
   */
  record PutLimitRangeViolation(String deploymentName, String reason) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putLimitRangeViolation(deploymentName, reason);
    }
  }

  record PutConfigEntry(ConfigEntry entry) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putConfigEntry(entry);
    }
  }

  record RemoveConfigEntry(String tenantId, String key) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeConfigEntry(tenantId, key);
    }
  }

  // Fully qualified deliberately: this package already declares its own Role (a Raft node's
  // FOLLOWER/CANDIDATE/LEADER state), which shadows an unqualified single-type-import of the RBAC
  // com.gimle.core.authz.Role of the same simple name -- same-package types always win Java's
  // unqualified-name resolution over an import, silently, with no compile error at the declaration
  // site (only at first attempted use of the wrong type, e.g. `new Role(...)`).
  record PutRole(com.gimle.core.authz.Role role) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRole(role);
    }
  }

  record RemoveRole(String name) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRole(name);
    }
  }

  record PutRoleBinding(RoleBinding binding) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putRoleBinding(binding);
    }
  }

  record RemoveRoleBinding(String id) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeRoleBinding(id);
    }
  }

  record PutAccount(Account account) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putAccount(account);
    }
  }

  record RemoveAccount(String username) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeAccount(username);
    }
  }

  record PutReconcilerInstanceState(ReconcilerInstanceState state) implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.putReconcilerInstanceState(state);
    }
  }

  record RemoveReconcilerInstanceState(String deploymentName, int instanceIndex)
      implements StateMutation {
    @Override
    public void applyTo(StateStore store) {
      store.removeReconcilerInstanceState(deploymentName, instanceIndex);
    }
  }

  /**
   * N independent mutations riding one log entry -- one consensus round and one WAL fsync for the
   * lot, applied in order. The group-commit lever for a caller (typically a reconciler tick, via
   * {@link MutationSink#proposeAll}) that would otherwise pay a full replication round trip per
   * mutation in a burst. Never nested: a batch of batches would buy nothing and only complicate
   * every consumer's reasoning about what one entry can hold.
   */
  record Batch(List<StateMutation> mutations) implements StateMutation {
    public Batch {
      if (mutations.isEmpty()) {
        throw new IllegalArgumentException("a mutation batch must not be empty");
      }
      if (mutations.stream().anyMatch(m -> m instanceof Batch)) {
        throw new IllegalArgumentException("a mutation batch must not contain another batch");
      }
      mutations = List.copyOf(mutations);
    }

    @Override
    public void applyTo(StateStore store) {
      for (StateMutation mutation : mutations) {
        mutation.applyTo(store);
      }
    }
  }
}
