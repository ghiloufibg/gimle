package com.gimle.mimir.raft;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
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
   * The compare-and-set (or other) precondition this mutation would check against {@code store}'s
   * current state, without applying anything -- {@link MutationOutcome#accepted()} for the
   * overwhelming majority that have none. Every guarded variant's own {@link #applyTo} checks this
   * itself first, so a bare proposal behaves exactly as before; {@link Batch} additionally checks
   * every member's precondition up front and applies nothing at all if any fails, which is what
   * makes a batch carrying guarded members all-or-nothing instead of silently half-applied.
   */
  default MutationOutcome precondition(StateStore store) {
    return MutationOutcome.accepted();
  }

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
    public MutationOutcome precondition(StateStore store) {
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
      return MutationOutcome.accepted();
    }

    @Override
    public MutationOutcome applyTo(StateStore store) {
      MutationOutcome guard = precondition(store);
      if (guard instanceof MutationOutcome.Rejected) {
        return guard;
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
    public MutationOutcome precondition(StateStore store) {
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
      return MutationOutcome.accepted();
    }

    @Override
    public MutationOutcome applyTo(StateStore store) {
      MutationOutcome guard = precondition(store);
      if (guard instanceof MutationOutcome.Rejected) {
        return guard;
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
   * Generation-guarded exactly like {@link PutDeployment} -- see its javadoc for the CAS contract.
   * The stored generation is store-assigned (current + 1); the proposer's {@code spec.generation()}
   * is carried only for the record and never trusted.
   */
  record PutKindDefinition(KindDefinitionSpec spec, long expectedGeneration)
      implements StateMutation {
    @Override
    public MutationOutcome precondition(StateStore store) {
      long current = store.getKindDefinitionGeneration(spec.kindName());
      if (current != expectedGeneration) {
        return MutationOutcome.rejected(
            "kind definition '"
                + spec.kindName()
                + "' is at generation "
                + current
                + ", expected "
                + expectedGeneration);
      }
      return MutationOutcome.accepted();
    }

    @Override
    public MutationOutcome applyTo(StateStore store) {
      MutationOutcome guard = precondition(store);
      if (guard instanceof MutationOutcome.Rejected) {
        return guard;
      }
      store.putKindDefinition(spec);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Refused while any instance of the kind still exists -- the store-level half of a
   * defense-in-depth pair with the API server's own 409, so no replay or alternate caller can ever
   * orphan stored instances with no schema left to validate or display them.
   */
  record RemoveKindDefinition(String kindName) implements StateMutation {
    @Override
    public MutationOutcome precondition(StateStore store) {
      int instanceCount = store.listCustomResources(kindName).size();
      if (instanceCount > 0) {
        return MutationOutcome.rejected(
            "kind '"
                + kindName
                + "' still has "
                + instanceCount
                + " instance(s) -- delete them first");
      }
      return MutationOutcome.accepted();
    }

    @Override
    public MutationOutcome applyTo(StateStore store) {
      MutationOutcome guard = precondition(store);
      if (guard instanceof MutationOutcome.Rejected) {
        return guard;
      }
      store.removeKindDefinition(kindName);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Generation-guarded exactly like {@link PutDeployment} -- a lost race surfaces to the client as
   * a 409, never a silent overwrite. The store bumps the generation itself and preserves any
   * already-reported status; see {@code StateStore#putCustomResource}.
   */
  record PutCustomResource(CustomResource resource, long expectedGeneration)
      implements StateMutation {
    @Override
    public MutationOutcome precondition(StateStore store) {
      long current =
          store.getCustomResourceGeneration(
              resource.kindName(), resource.tenantId(), resource.name());
      if (current != expectedGeneration) {
        return MutationOutcome.rejected(
            "resource '"
                + resource.kindName()
                + "/"
                + resource.name()
                + "' is at generation "
                + current
                + ", expected "
                + expectedGeneration);
      }
      return MutationOutcome.accepted();
    }

    @Override
    public MutationOutcome applyTo(StateStore store) {
      MutationOutcome guard = precondition(store);
      if (guard instanceof MutationOutcome.Rejected) {
        return guard;
      }
      store.putCustomResource(resource);
      return MutationOutcome.accepted();
    }
  }

  record RemoveCustomResource(String kindName, Optional<String> tenantId, String name)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.removeCustomResource(kindName, tenantId, name);
      return MutationOutcome.accepted();
    }
  }

  /**
   * Last-write-wins and never bumps the generation -- operators embed {@code observedGeneration} in
   * the status JSON itself, and a stale status self-corrects on the operator's next level-triggered
   * pass. See {@code StateStore#putCustomResourceStatus}.
   */
  record PutCustomResourceStatus(
      String kindName, Optional<String> tenantId, String name, byte[] statusJson)
      implements StateMutation {
    @Override
    public MutationOutcome applyTo(StateStore store) {
      store.putCustomResourceStatus(kindName, tenantId, name, statusJson);
      return MutationOutcome.accepted();
    }
  }

  /**
   * N independent mutations riding one log entry -- one consensus round and one WAL fsync for the
   * lot, applied in order. The group-commit lever for a caller (typically a reconciler tick, via
   * {@link MutationSink#proposeAll}) that would otherwise pay a full replication round trip per
   * mutation in a burst. Never nested: a batch of batches would buy nothing and only complicate
   * every consumer's reasoning about what one entry can hold. Guarded members are all-or-nothing --
   * see {@link #applyTo} -- with their preconditions checked against the pre-batch state, so a
   * batch must never carry a guarded member that depends on an earlier member's own effect.
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
      // All-or-nothing for guarded members: every member's precondition is checked against the
      // pre-batch state first, and one failure rejects the whole entry with nothing applied --
      // there is no rollback, so applying members before discovering a later member's stale CAS
      // would leave a half-applied batch no caller could reason about. Preconditions target
      // distinct resources per member in practice (a batch never carries two guarded writes to
      // the same key), so checking them all against the pre-batch state is exact, not
      // approximate. Applied under Raft's serial log-application, so no other writer can
      // interleave between the check pass and the apply pass.
      for (StateMutation mutation : mutations) {
        MutationOutcome guard = mutation.precondition(store);
        if (guard instanceof MutationOutcome.Rejected) {
          return guard;
        }
      }
      for (StateMutation mutation : mutations) {
        mutation.applyTo(store);
      }
      return MutationOutcome.accepted();
    }
  }
}
