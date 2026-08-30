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
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every mutating operation {@link StateStore} exposes, replicated through the Raft log -- one
 * variant per {@code StateStore} method that changes durable state, applied to the store via {@link
 * #applyTo} once a {@link RaftNode} commits the entry. {@code putNodeHeartbeat} deliberately has no
 * variant here: heartbeats are high-frequency, tolerate a brief gap after a leader change, and
 * would make the log's write rate scale with cluster size for no correctness benefit -- only the
 * leader's own {@code StateStore} ever receives them, outside the log entirely.
 */
public sealed interface StateMutation extends RaftLogPayload {

  MutationOutcome applyTo(StateStore store);

  /**
   * Generation-guarded: {@code expectedGeneration} is the value the proposer last read via {@link
   * StateStore#getDeploymentGeneration}. Applied identically on every node from the same prior
   * state (Raft's usual determinism guarantee), so a mismatch is a real, cluster-wide fact about
   * what committed in between -- not a leader-side guess -- and the store is left untouched rather
   * than silently overwritten. This is the compare-and-set half of what closes the concurrent
   * apply/delete race a plain unconditional {@code putDeployment} could not: a racing {@code
   * RemoveDeployment} that committed first bumps the generation (to 0, via removal) out from under
   * this one, so a stale apply can no longer resurrect a deployment someone else just deleted.
   */
  record PutDeployment(DeploymentSpec spec, long expectedGeneration) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      long current = store.getDeploymentGeneration(spec.tenantId(), spec.name());
      if (current != expectedGeneration) {
        return MutationOutcome.rejected(
            "deployment '"
                + spec.name()
                + "' is at generation "
                + current
                + ", expected "
                + expectedGeneration);
      }
      store.putDeployment(spec);
      return MutationOutcome.accepted();
    }
  }

  /**
   * The delete counterpart to {@link PutDeployment}'s own generation guard -- see its javadoc for
   * the full race this closes. Rejecting rather than unconditionally removing means a delete whose
   * caller last observed generation {@code G} never discards a deployment a racing apply changed
   * (to a different generation) after that read; {@code ApiServer} re-reads on rejection to decide
   * whether the caller's actual goal (the name being gone) was already met by someone else's write,
   * or whether a genuinely different, newer state exists that the caller never asked to discard.
   */
  record RemoveDeployment(Optional<String> tenantId, String name, long expectedGeneration)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      long current = store.getDeploymentGeneration(tenantId, name);
      if (current != expectedGeneration) {
        return MutationOutcome.rejected(
            "deployment '"
                + name
                + "' is at generation "
                + current
                + ", expected "
                + expectedGeneration);
      }
      store.removeDeployment(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutService(ServiceSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putService(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveService(Optional<String> tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeService(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutNetworkPolicy(NetworkPolicySpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putNetworkPolicy(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveNetworkPolicy(String tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeNetworkPolicy(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutAssignment(InstanceAssignment assignment) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putAssignment(assignment);
      return MutationOutcome.accepted();
    }
  }

  record RemoveAssignment(Optional<String> tenantId, String deploymentName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeAssignment(tenantId, deploymentName, instanceIndex);
      return MutationOutcome.accepted();
    }
  }

  record PutJobSpec(JobSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putJobSpec(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveJobSpec(Optional<String> tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeJobSpec(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutJobRun(JobRun run) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putJobRun(run);
      return MutationOutcome.accepted();
    }
  }

  record RemoveJobRun(Optional<String> tenantId, String jobName, int attempt)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeJobRun(tenantId, jobName, attempt);
      return MutationOutcome.accepted();
    }
  }

  record PutJobPhase(Optional<String> tenantId, String jobName, JobPhase phase)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putJobPhase(tenantId, jobName, phase);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Always proposed in the same batch as the {@link PutJobPhase} that makes a job terminal, so the
   * last attempt's own detail survives the {@link RemoveJobRun} that same batch performs -- see
   * {@link JobRunSummary}'s own javadoc for why the two records are kept separate.
   */
  record PutJobRunSummary(JobRunSummary summary) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putJobRunSummary(summary);
      return MutationOutcome.accepted();
    }
  }

  record PutCronJobSpec(CronJobSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putCronJobSpec(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveCronJobSpec(Optional<String> tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeCronJobSpec(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutCronJobLastSchedule(Optional<String> tenantId, String name, Instant lastScheduleTime)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putCronJobLastSchedule(tenantId, name, lastScheduleTime);
      return MutationOutcome.accepted();
    }
  }

  record PutDaemonSetSpec(DaemonSetSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putDaemonSetSpec(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveDaemonSetSpec(Optional<String> tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeDaemonSetSpec(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutDaemonSetAssignment(DaemonSetAssignment assignment) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putDaemonSetAssignment(assignment);
      return MutationOutcome.accepted();
    }
  }

  record RemoveDaemonSetAssignment(Optional<String> tenantId, String daemonSetName, String nodeId)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeDaemonSetAssignment(tenantId, daemonSetName, nodeId);
      return MutationOutcome.accepted();
    }
  }

  record AddRollingDaemonSetNode(Optional<String> tenantId, String daemonSetName, String nodeId)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.addRollingDaemonSetNode(tenantId, daemonSetName, nodeId);
      return MutationOutcome.accepted();
    }
  }

  record RemoveRollingDaemonSetNode(Optional<String> tenantId, String daemonSetName, String nodeId)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeRollingDaemonSetNode(tenantId, daemonSetName, nodeId);
      return MutationOutcome.accepted();
    }
  }

  record PutStatefulSetSpec(StatefulSetSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putStatefulSetSpec(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveStatefulSetSpec(Optional<String> tenantId, String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeStatefulSetSpec(tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  record PutStatefulSetAssignment(StatefulSetAssignment assignment) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putStatefulSetAssignment(assignment);
      return MutationOutcome.accepted();
    }
  }

  record RemoveStatefulSetAssignment(
      Optional<String> tenantId, String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeStatefulSetAssignment(tenantId, statefulSetName, instanceIndex);
      return MutationOutcome.accepted();
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
  record PutRollingStatefulSetIndex(
      Optional<String> tenantId, String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putRollingStatefulSetIndex(tenantId, statefulSetName, instanceIndex);
      return MutationOutcome.accepted();
    }
  }

  record ClearRollingStatefulSetIndex(Optional<String> tenantId, String statefulSetName)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.clearRollingStatefulSetIndex(tenantId, statefulSetName);
      return MutationOutcome.accepted();
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
  record PutStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex, String nodeId)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putStatefulSetIndexNode(tenantId, statefulSetName, instanceIndex, nodeId);
      return MutationOutcome.accepted();
    }
  }

  record RemoveStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeStatefulSetIndexNode(tenantId, statefulSetName, instanceIndex);
      return MutationOutcome.accepted();
    }
  }

  record AddRollingIndex(Optional<String> tenantId, String deploymentName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.addRollingIndex(tenantId, deploymentName, instanceIndex);
      return MutationOutcome.accepted();
    }
  }

  record RemoveRollingIndex(Optional<String> tenantId, String deploymentName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeRollingIndex(tenantId, deploymentName, instanceIndex);
      return MutationOutcome.accepted();
    }
  }

  /**
   * The surge counterpart to {@link AddRollingIndex}: marks {@code surgeIndex} (a synthetic index
   * {@code >= replicas}) as provisioning a replacement for {@code targetIndex} ahead of removing
   * the original -- see {@code DeploymentReconciler#handleSurge}.
   */
  record AddSurgeIndex(
      Optional<String> tenantId, String deploymentName, int surgeIndex, int targetIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.addSurgeIndex(tenantId, deploymentName, surgeIndex, targetIndex);
      return MutationOutcome.accepted();
    }
  }

  record RemoveSurgeIndex(Optional<String> tenantId, String deploymentName, int surgeIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeSurgeIndex(tenantId, deploymentName, surgeIndex);
      return MutationOutcome.accepted();
    }
  }

  record PutEffectiveReplicas(Optional<String> tenantId, String deploymentName, int replicas)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putEffectiveReplicas(tenantId, deploymentName, replicas);
      return MutationOutcome.accepted();
    }
  }

  record PutNodeRegistration(NodeRegistration registration) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putNodeRegistration(registration);
      return MutationOutcome.accepted();
    }
  }

  record PutTenant(Tenant tenant) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putTenant(tenant);
      return MutationOutcome.accepted();
    }
  }

  record RemoveTenant(String id) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeTenant(id);
      return MutationOutcome.accepted();
    }
  }

  record PutQuotaViolation(Optional<String> tenantId, String deploymentName, boolean violating)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putQuotaViolation(tenantId, deploymentName, violating);
      return MutationOutcome.accepted();
    }
  }

  record PutNodeCordon(String nodeId, boolean cordoned) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putNodeCordon(nodeId, cordoned);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Adds (or removes) one tenant from a node's taint set -- see {@code StateStore#putNodeTaint}'s
   * own javadoc for the scheduling semantics this backs.
   */
  record PutNodeTaint(String nodeId, String tenantId, boolean tainted) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putNodeTaint(nodeId, tenantId, tainted);
      return MutationOutcome.accepted();
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
    public MutationOutcome applyTo(StateStore store) {
      store.putCertificateRevocation(serialNumber, revoked);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Advances one username's session "revoked before" watermark -- {@code
   * ApiServer#handleAuthLogout} proposes this for whichever username the logged-out cookie verified
   * to, and {@code ApiServer#resolvePrincipal} rejects any session cookie for that username issued
   * at or before it, even though its HMAC signature still verifies. {@code revokedBeforeEpochMilli}
   * is stamped once by the proposing replica and carried in the mutation, the same determinism
   * reasoning {@link PutWorkloadToken#mintedAtEpochMilli} documents for its own wall-clock stamp.
   */
  record PutSessionRevocation(String username, long revokedBeforeEpochMilli)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putSessionRevocation(username, revokedBeforeEpochMilli);
      return MutationOutcome.accepted();
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
    public MutationOutcome applyTo(StateStore store) {
      store.putWorkloadToken(record, mintedAtEpochMilli);
      return MutationOutcome.accepted();
    }
  }

  record RemoveWorkloadToken(String key) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeWorkloadToken(key);
      return MutationOutcome.accepted();
    }
  }

  /**
   * No corresponding {@code RemoveInstanceEvent} -- retention-cap pruning is internal to {@link
   * StateStore#putInstanceEvent}, applied identically on every replica as this same mutation is
   * replayed, not a separate mutation of its own.
   */
  record AppendInstanceEvent(Optional<String> tenantId, InstanceEvent event)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putInstanceEvent(tenantId, event);
      return MutationOutcome.accepted();
    }
  }

  /**
   * No corresponding {@code RemoveAuditEvent} for the same reason {@code AppendInstanceEvent} has
   * none -- retention-cap pruning is internal to {@link StateStore#putAuditEvent}, applied
   * identically on every replica as this mutation replays.
   */
  record AppendAuditEvent(AuditEvent event) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putAuditEvent(event);
      return MutationOutcome.accepted();
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
    public MutationOutcome applyTo(StateStore store) {
      store.putControllerRevision(revision);
      return MutationOutcome.accepted();
    }
  }

  record PutLimitRange(LimitRangeSpec spec) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putLimitRange(spec);
      return MutationOutcome.accepted();
    }
  }

  record RemoveLimitRange(String tenantId) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeLimitRange(tenantId);
      return MutationOutcome.accepted();
    }
  }

  /**
   * A blank {@code reason} clears the violation; a non-blank one sets it -- same convention {@link
   * com.gimle.mimir.store.StateStore#putLimitRangeViolation} documents on its own.
   */
  record PutLimitRangeViolation(Optional<String> tenantId, String deploymentName, String reason)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putLimitRangeViolation(tenantId, deploymentName, reason);
      return MutationOutcome.accepted();
    }
  }

  record PutConfigEntry(ConfigEntry entry) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putConfigEntry(entry);
      return MutationOutcome.accepted();
    }
  }

  record RemoveConfigEntry(String tenantId, String key) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeConfigEntry(tenantId, key);
      return MutationOutcome.accepted();
    }
  }

  // Fully qualified deliberately: this package already declares its own Role (a Raft node's
  // FOLLOWER/CANDIDATE/LEADER state), which shadows an unqualified single-type-import of the RBAC
  // com.gimle.core.authz.Role of the same simple name -- same-package types always win Java's
  // unqualified-name resolution over an import, silently, with no compile error at the declaration
  // site (only at first attempted use of the wrong type, e.g. `new Role(...)`).
  record PutRole(com.gimle.core.authz.Role role) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putRole(role);
      return MutationOutcome.accepted();
    }
  }

  record RemoveRole(String name) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeRole(name);
      return MutationOutcome.accepted();
    }
  }

  record PutRoleBinding(RoleBinding binding) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putRoleBinding(binding);
      return MutationOutcome.accepted();
    }
  }

  record RemoveRoleBinding(String id) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeRoleBinding(id);
      return MutationOutcome.accepted();
    }
  }

  record PutAccount(Account account) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putAccount(account);
      return MutationOutcome.accepted();
    }
  }

  record RemoveAccount(String username) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeAccount(username);
      return MutationOutcome.accepted();
    }
  }

  record PutReconcilerInstanceState(ReconcilerInstanceState state) implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putReconcilerInstanceState(state);
      return MutationOutcome.accepted();
    }
  }

  record RemoveReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeReconcilerInstanceState(tenantId, deploymentName, instanceIndex);
      return MutationOutcome.accepted();
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
    public MutationOutcome applyTo(StateStore store) {
      // No batched mutation is CAS-guarded today (PutDeployment/RemoveDeployment, the only two
      // that can reject, are proposed bare by ApiServer, never wrapped in a Batch), so there is no
      // partial-rejection semantics to define yet -- every member always applies and this always
      // reports accepted. A future CAS-guarded mutation entering a Batch would need this revisited.
      for (StateMutation mutation : mutations) {
        mutation.applyTo(store);
      }
      return MutationOutcome.accepted();
    }
  }
}
