package com.gimle.mimir.store;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The purely in-memory state machine the Raft log applies committed mutations to: concurrent maps,
 * one per resource kind, and nothing on disk of its own. Durability lives one layer down, in {@code
 * RaftLog}'s write-ahead log -- recovery is that log's persisted snapshot (installed here via
 * {@link #restoreFromSnapshot}) plus committed-entry replay, the same way a follower catches up
 * live. Deliberately not an embedded storage engine: with every mutation already fsynced in the log
 * before it's applied here, a second write per resource would buy durability nothing.
 */
public final class StateStore implements StoreReader {

  private static final Logger log = LoggerFactory.getLogger(StateStore.class);

  private final Clock clock;
  private final Map<String, DeploymentSpec> deployments = new ConcurrentHashMap<>();
  private final Map<String, Long> deploymentGenerations = new ConcurrentHashMap<>();
  private final Map<String, ServiceSpec> services = new ConcurrentHashMap<>();
  private final Map<String, NetworkPolicySpec> networkPolicies = new ConcurrentHashMap<>();
  private final Map<String, IngressSpec> ingresses = new ConcurrentHashMap<>();
  private final Map<String, AlertRuleSpec> alertRules = new ConcurrentHashMap<>();
  // Durable verdict for whether an AlertRuleSpec is currently firing -- see
  // putAlertFiringState's own javadoc for the absent/true/false three-state meaning this map
  // carries.
  private final Map<String, Boolean> alertFiringState = new ConcurrentHashMap<>();
  private final Map<String, InstanceAssignment> assignments = new ConcurrentHashMap<>();
  private final Map<String, JobSpec> jobSpecs = new ConcurrentHashMap<>();
  private final Map<String, JobRun> jobRuns = new ConcurrentHashMap<>();
  // Only ever holds an entry once a job reaches a terminal phase -- absent means "still running"
  // (no attempt placed yet, or one currently in flight), the same "only persist the override"
  // posture effectiveReplicas/rollingIndices already use rather than writing an explicit RUNNING
  // marker for every job that hasn't finished yet.
  private final Map<String, JobPhase> jobPhases = new ConcurrentHashMap<>();
  // Only ever holds an entry once a job reaches a terminal phase too, populated in the very same
  // batch that removes that job's last JobRun -- see JobRunSummary's own javadoc for why the two
  // records exist separately.
  private final Map<String, JobRunSummary> jobRunSummaries = new ConcurrentHashMap<>();
  private final Map<String, CronJobSpec> cronJobSpecs = new ConcurrentHashMap<>();
  // Absent means "never fired yet" -- CronJobReconciler treats that as "start looking forward from
  // now," never retroactively firing every missed minute since epoch. See CronJobReconciler's own
  // javadoc.
  private final Map<String, Instant> cronJobLastSchedule = new ConcurrentHashMap<>();
  private final Map<String, DaemonSetSpec> daemonSetSpecs = new ConcurrentHashMap<>();
  private final Map<String, DaemonSetAssignment> daemonSetAssignments = new ConcurrentHashMap<>();
  // The nodeIds currently mid-rollout for a DaemonSet, if any -- the node-keyed counterpart to
  // rollingIndices above, same "only persist while in flight" shape. Sized by the deployment's own
  // effective maxUnavailable (small, bounded), not by cluster size.
  private final Map<String, Set<String>> rollingDaemonSetNodes = new ConcurrentHashMap<>();
  // The eligible-node count DaemonSetReconciler computed on its most recent tick -- "desired",
  // read by the API server's DaemonSet status surface alongside the placed (assignment) count.
  // Absent until the first tick after the spec was admitted, the same "not yet known" shape
  // getEffectiveReplicas already uses.
  private final Map<String, Integer> daemonSetDesiredCounts = new ConcurrentHashMap<>();
  private final Map<String, StatefulSetSpec> statefulSetSpecs = new ConcurrentHashMap<>();
  // Keyed by statefulSetAssignmentKey (statefulSetName#instanceIndex) -- a real per-index key,
  // unlike DaemonSetAssignment's node-keyed one, since a StatefulSet index is a stable identity
  // exactly like an ordinary deployment replica's own instanceIndex.
  private final Map<String, StatefulSetAssignment> statefulSetAssignments =
      new ConcurrentHashMap<>();
  // The single index currently in flight for a StatefulSet, if any -- reused for both
  // OrderedReady scale-up admission and rolling-update admission (see StateMutation
  // .PutRollingStatefulSetIndex's own javadoc), same "only persist while in flight" shape as
  // rollingIndices/rollingDaemonSetNodes above. A separate map from rollingIndices: the two
  // resource kinds never share a namespace.
  private final Map<String, Integer> rollingStatefulSetIndices = new ConcurrentHashMap<>();
  // The sticky node binding for one StatefulSet index, keyed by statefulSetAssignmentKey --
  // survives an ordinary assignment removal (mid-rollout, or a dark node), cleared only on
  // genuinely permanent removal (scale-down below the index, or the whole spec deleted). See
  // StateMutation.PutStatefulSetIndexNode's own javadoc for why this can't just be read off the
  // current StatefulSetAssignment record.
  private final Map<String, String> statefulSetIndexNodes = new ConcurrentHashMap<>();
  private final Map<String, NodeRegistration> nodeRegistrations = new ConcurrentHashMap<>();
  private final Map<String, ObservedHeartbeat> nodeHeartbeats = new ConcurrentHashMap<>();
  private final Map<String, LeaseState> leases = new ConcurrentHashMap<>();
  // The instanceIndices currently mid-rollout for a Deployment, if any -- one entry per index
  // actively being replaced, sized by the deployment's own effective maxUnavailable (small,
  // bounded: see DisruptionBudget), not by replica count.
  private final Map<String, Set<Integer>> rollingIndices = new ConcurrentHashMap<>();
  // The surge slots currently in flight for a Deployment, if any -- surgeIndex (a synthetic index
  // >= replicas) -> targetIndex (the real 0..replicas-1 index it will replace once ready), sized
  // by the deployment's own effective maxSurge (small, bounded), not by replica count. A separate
  // map from rollingIndices: maxUnavailable and maxSurge are independent budgets, and a given
  // mismatched index is only ever tracked by one of the two at a time.
  private final Map<String, Map<Integer, Integer>> surgeIndices = new ConcurrentHashMap<>();
  private final Map<String, Integer> effectiveReplicas = new ConcurrentHashMap<>();
  // When the autoscaler last actually moved a deployment's effectiveReplicas. Absent means "never
  // scaled," which no stabilization window can ever suppress. Durable rather than a field on the
  // reconciler so a control-plane restart or failover onto another replica can't reset the window
  // and let a flapping metric scale again immediately.
  private final Map<String, Instant> deploymentLastScale = new ConcurrentHashMap<>();
  private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
  private final Map<String, Boolean> quotaViolations = new ConcurrentHashMap<>();
  private final Map<String, Boolean> nodeCordons = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> nodeTaints = new ConcurrentHashMap<>();
  private final Map<String, WorkloadHealthState> workloadHealthStates = new ConcurrentHashMap<>();
  private final Map<String, Boolean> revokedCertificateSerials = new ConcurrentHashMap<>();
  private final Map<Byte, Boolean> retiredSecretsKeyIds = new ConcurrentHashMap<>();
  private final Map<String, WorkloadTokenRecord> workloadTokens = new ConcurrentHashMap<>();
  // username -> the epoch-milli watermark set by that user's last console logout. Bounded by the
  // number of distinct usernames that have ever logged out (an operator account list, not
  // per-request churn), so unlike workloadTokens above this needs no expired-entry sweep: once
  // every session issued before a username's watermark has long since hit its own 12-hour expiry,
  // the entry is simply never consulted again, harmless to leave in place.
  private final Map<String, Long> sessionRevokedBeforeEpochMilli = new ConcurrentHashMap<>();
  private final Map<String, ConfigEntry> configEntries = new ConcurrentHashMap<>();
  private final Map<String, Role> roles = new ConcurrentHashMap<>();
  private final Map<String, RoleBinding> roleBindings = new ConcurrentHashMap<>();
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();
  private final Map<String, ReconcilerInstanceState> reconcilerInstanceStates =
      new ConcurrentHashMap<>();
  private final Map<String, LimitRangeSpec> limitRanges = new ConcurrentHashMap<>();
  private final Map<String, KindDefinitionSpec> kindDefinitions = new ConcurrentHashMap<>();
  private final Map<String, CustomResource> customResources = new ConcurrentHashMap<>();

  /** Keyed by deployment name; absence means not violating. The value is the violation reason. */
  private final Map<String, String> limitRangeViolations = new ConcurrentHashMap<>();

  /**
   * The store's first many-per-key resource: every other map here holds at most one current value
   * per key, but an instance's lifecycle timeline is inherently a bounded history, not a single
   * current value -- see {@link #putInstanceEvent} for how the per-instance retention cap keeps
   * "many" bounded rather than unbounded.
   */
  private final Map<String, List<InstanceEvent>> instanceEvents = new ConcurrentHashMap<>();

  /**
   * Oldest events beyond this count (per instance) are pruned on the next {@link #putInstanceEvent}
   * -- keeps each instance's timeline small and bounded rather than growing forever, matching
   * {@code causeSummary}'s own "footprint over completeness" trade-off.
   */
  private static final int MAX_EVENTS_PER_INSTANCE = 50;

  /**
   * The cross-resource audit trail, cluster-wide rather than per-key like {@link #instanceEvents}
   * -- there's no natural single-key scope for "every mutating authorization decision," unlike an
   * instance's own lifecycle timeline. Oldest-first internally (appended at the end, pruned from
   * the front), matching {@link #instanceEvents}' own internal order; {@link #listAuditEvents}
   * reverses for newest-first reads. Access is guarded by {@link #auditEventsLock} rather than a
   * concurrent collection: append-then-prune must be atomic against the *whole* list, not per-key
   * the way {@link ConcurrentHashMap#compute} makes {@link #putInstanceEvent} atomic for free.
   */
  private final List<AuditEvent> auditEvents = new ArrayList<>();

  private final Object auditEventsLock = new Object();

  // Guarded by auditEventsLock, same as auditEvents itself -- the ring-buffer's own running total
  // of everything the MAX_AUDIT_EVENTS cap has ever discarded, surfaced through
  // #auditTrailStatus() so a caller reviewing the trail can tell "complete record" from
  // "truncated" instead of that transition happening silently.
  private long auditEventsEvictedTotal;

  /**
   * How often a genuinely chatty cluster's eviction gets its own log line, once the cap is first
   * reached -- every single eviction beyond the first would mean one log line per audited write
   * forever, drowning out everything else; this keeps the signal ("this is still happening")
   * without the flood.
   */
  private static final long AUDIT_EVICTION_LOG_INTERVAL = 1000;

  /**
   * Oldest events beyond this count are pruned on the next {@link #putAuditEvent} -- a generous
   * cluster-wide cap (vs. {@link #MAX_EVENTS_PER_INSTANCE}'s much smaller per-instance one), since
   * this is the only history across every resource kind, not one instance's own timeline.
   */
  static final int MAX_AUDIT_EVENTS = 50_000;

  /**
   * A Deployment/StatefulSet/DaemonSet's revision history, keyed by {@link
   * ControllerRevision#revisionKey} -- the second many-per-key resource this store holds, same
   * shape as {@link #instanceEvents}: bounded by {@link #MAX_REVISIONS_PER_WORKLOAD}, not a single
   * current value.
   */
  private final Map<String, List<ControllerRevision>> controllerRevisions =
      new ConcurrentHashMap<>();

  /**
   * Oldest revisions beyond this count (per workload) are pruned on the next {@link
   * #putControllerRevision} -- matches Kubernetes' own default {@code revisionHistoryLimit} for
   * {@code StatefulSet}/{@code DaemonSet}.
   */
  private static final int MAX_REVISIONS_PER_WORKLOAD = 10;

  public StateStore() {
    this(Clock.systemUTC());
  }

  /**
   * Injectable-clock variant, for tests that need to reason about how <em>old</em> a heartbeat or a
   * lease is. Those two are the only timestamps this store stamps itself; everything else it holds
   * was timestamped by whoever proposed the mutation. Without this seam a test can advance a
   * reconciler's clock but not the store's, so "this node has been silent for 20 seconds" is not
   * expressible at all -- the heartbeat always looks like it arrived just now.
   */
  public StateStore(Clock clock) {
    this.clock = clock;
  }

  // ---- deployments ----

  public void putDeployment(DeploymentSpec spec) {
    String key = scopedKey(spec.tenantId(), spec.name());
    deployments.put(key, spec);
    deploymentGenerations.merge(key, 1L, Long::sum);
  }

  public Optional<DeploymentSpec> getDeployment(Optional<String> tenantId, String name) {
    return Optional.ofNullable(deployments.get(scopedKey(tenantId, name)));
  }

  /**
   * See {@link StoreReader#getDeploymentGeneration}'s own javadoc for the CAS contract this backs.
   */
  @Override
  public long getDeploymentGeneration(Optional<String> tenantId, String name) {
    return deploymentGenerations.getOrDefault(scopedKey(tenantId, name), 0L);
  }

  public List<DeploymentSpec> listDeployments() {
    return List.copyOf(deployments.values());
  }

  /**
   * Also clears this name's {@link ControllerRevision} history and every instance's {@link
   * InstanceEvent} timeline -- without this, a deleted Deployment's revisions stay keyed under
   * {@link ControllerRevision#revisionKey} and its events under {@link #instanceEventsKey},
   * orphaned indefinitely, and a later {@code apply} that recreates the same name would inherit
   * both as if the new Deployment were a continuation of the old one: {@code nextRevisionFor} would
   * keep incrementing from the old revision number instead of starting fresh, a caller could roll
   * back to a revision from an entirely different, already-deleted Deployment that merely happened
   * to share this name, and the new instances' event timelines would open with the old Deployment's
   * history already in them. Since {@link StateMutation} is applied in strict Raft log order with
   * no concurrent writers, clearing here is enough on its own to make a delete-then-recreate a
   * clean break -- no separate identity token is needed to protect against a race that can't
   * happen.
   *
   * <p>The single thing deliberately not cleared is this name's generation counter, which is bumped
   * here like any other write instead. That counter is the compare-and-set token {@link
   * com.gimle.mimir.raft.StateMutation.PutDeployment} checks its precondition against, and an entry
   * carries the generation its proposer read at propose time while the check itself only runs when
   * the entry applies -- which, across a leader change, can be arbitrarily later. Dropping the key
   * would reset the counter to its absent default, making "deleted" indistinguishable from "never
   * existed": a proposal captured while the name was still free would find its precondition true a
   * second time and resurrect a Deployment deleted in between. Staying monotonic across deletion is
   * what makes that stale proposal fail instead, at the cost of one long per Deployment name ever
   * used -- the bound every monotonic resource-version scheme carries, and why this is the one
   * exception to the wholesale cleanup above.
   */
  public void removeDeployment(Optional<String> tenantId, String name) {
    String key = scopedKey(tenantId, name);
    deployments.remove(key);
    deploymentGenerations.merge(key, 1L, Long::sum);
    clearAllRollingIndices(tenantId, name);
    clearAllSurgeIndices(tenantId, name);
    effectiveReplicas.remove(key);
    deploymentLastScale.remove(key);
    controllerRevisions.remove(ControllerRevision.revisionKey("Deployment", tenantId, name));
    clearInstanceEventsFor(tenantId, name);
  }

  // ---- services ----

  public void putService(ServiceSpec spec) {
    services.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<ServiceSpec> getService(Optional<String> tenantId, String name) {
    return Optional.ofNullable(services.get(scopedKey(tenantId, name)));
  }

  public List<ServiceSpec> listServices() {
    return List.copyOf(services.values());
  }

  public void removeService(Optional<String> tenantId, String name) {
    services.remove(scopedKey(tenantId, name));
  }

  // ---- network policies ----

  public void putNetworkPolicy(NetworkPolicySpec spec) {
    networkPolicies.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<NetworkPolicySpec> getNetworkPolicy(String tenantId, String name) {
    return Optional.ofNullable(networkPolicies.get(scopedKey(tenantId, name)));
  }

  public List<NetworkPolicySpec> listNetworkPolicies() {
    return List.copyOf(networkPolicies.values());
  }

  public void removeNetworkPolicy(String tenantId, String name) {
    networkPolicies.remove(scopedKey(tenantId, name));
  }

  // ---- ingresses ----

  public void putIngress(IngressSpec spec) {
    ingresses.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<IngressSpec> getIngress(String tenantId, String name) {
    return Optional.ofNullable(ingresses.get(scopedKey(tenantId, name)));
  }

  public List<IngressSpec> listIngresses() {
    return List.copyOf(ingresses.values());
  }

  public void removeIngress(String tenantId, String name) {
    ingresses.remove(scopedKey(tenantId, name));
  }

  // ---- alert rules ----

  public void putAlertRule(AlertRuleSpec spec) {
    alertRules.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<AlertRuleSpec> getAlertRule(Optional<String> tenantId, String name) {
    return Optional.ofNullable(alertRules.get(scopedKey(tenantId, name)));
  }

  public List<AlertRuleSpec> listAlertRules() {
    return List.copyOf(alertRules.values());
  }

  public void removeAlertRule(Optional<String> tenantId, String name) {
    String key = scopedKey(tenantId, name);
    alertRules.remove(key);
    // Cascades to the rule's own firing verdict -- an equally-named rule created afterward starts
    // from "never evaluated" rather than inheriting a deleted rule's stale transition, the same
    // reasoning RemoveRole's own cascade documents for RoleBinding.
    alertFiringState.remove(key);
  }

  /**
   * Whether {@code name} is currently firing, replicated through Raft so every control-plane
   * replica reports the same verdict and a replica restart never resets it -- what moved this out
   * of {@code AlertReconciler}'s own process. Absent means the rule has never crossed or resolved
   * since it (or a same-named predecessor) was created; an explicit {@code false} means a real,
   * previously-observed resolution, a genuinely different fact from "never evaluated yet."
   */
  public void putAlertFiringState(Optional<String> tenantId, String name, boolean firing) {
    alertFiringState.put(scopedKey(tenantId, name), firing);
  }

  public Optional<Boolean> getAlertFiringState(Optional<String> tenantId, String name) {
    return Optional.ofNullable(alertFiringState.get(scopedKey(tenantId, name)));
  }

  // ---- assignments ----

  public void putAssignment(InstanceAssignment assignment) {
    assignments.put(
        assignmentKey(
            assignment.tenantId(), assignment.deploymentName(), assignment.instanceIndex()),
        assignment);
  }

  public void removeAssignment(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    assignments.remove(assignmentKey(tenantId, deploymentName, instanceIndex));
  }

  public List<InstanceAssignment> listAssignments() {
    return List.copyOf(assignments.values());
  }

  public List<InstanceAssignment> listAssignmentsFor(
      Optional<String> tenantId, String deploymentName) {
    return assignments.values().stream()
        .filter(a -> a.tenantId().equals(tenantId) && a.deploymentName().equals(deploymentName))
        .toList();
  }

  // ---- jobs ----

  public void putJobSpec(JobSpec spec) {
    jobSpecs.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<JobSpec> getJobSpec(Optional<String> tenantId, String name) {
    return Optional.ofNullable(jobSpecs.get(scopedKey(tenantId, name)));
  }

  public List<JobSpec> listJobSpecs() {
    return List.copyOf(jobSpecs.values());
  }

  /**
   * Also clears any terminal {@link JobPhase} recorded for {@code name} -- a removed job has no
   * phase to report back, terminal or otherwise. Does not remove any {@link JobRun}s still on disk
   * for it; {@code JobReconciler} is responsible for tearing those down first, the same "assignment
   * gone -&gt; agent stops it" ordering {@code DeploymentReconciler} already relies on for {@code
   * InstanceAssignment}.
   */
  public void removeJobSpec(Optional<String> tenantId, String name) {
    String key = scopedKey(tenantId, name);
    jobSpecs.remove(key);
    jobPhases.remove(key);
    jobRunSummaries.remove(key);
  }

  // ---- job runs ----

  public void putJobRun(JobRun run) {
    jobRuns.put(jobRunKey(run.tenantId(), run.jobName(), run.attempt()), run);
  }

  public void removeJobRun(Optional<String> tenantId, String jobName, int attempt) {
    jobRuns.remove(jobRunKey(tenantId, jobName, attempt));
  }

  public List<JobRun> listJobRuns() {
    return List.copyOf(jobRuns.values());
  }

  public List<JobRun> listJobRunsFor(Optional<String> tenantId, String jobName) {
    return jobRuns.values().stream()
        .filter(r -> r.tenantId().equals(tenantId) && r.jobName().equals(jobName))
        .toList();
  }

  // ---- job phase ----

  public void putJobPhase(Optional<String> tenantId, String jobName, JobPhase phase) {
    jobPhases.put(scopedKey(tenantId, jobName), phase);
  }

  /** Empty means "not yet terminal" -- see {@link #jobPhases}'s own field javadoc. */
  public Optional<JobPhase> getJobPhase(Optional<String> tenantId, String jobName) {
    return Optional.ofNullable(jobPhases.get(scopedKey(tenantId, jobName)));
  }

  // ---- job run summary ----

  public void putJobRunSummary(JobRunSummary summary) {
    jobRunSummaries.put(scopedKey(summary.tenantId(), summary.jobName()), summary);
  }

  /** Empty until the job reaches a terminal phase -- see {@link #jobRunSummaries}'s own javadoc. */
  public Optional<JobRunSummary> getJobRunSummary(Optional<String> tenantId, String jobName) {
    return Optional.ofNullable(jobRunSummaries.get(scopedKey(tenantId, jobName)));
  }

  // ---- cronjobs ----

  public void putCronJobSpec(CronJobSpec spec) {
    cronJobSpecs.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<CronJobSpec> getCronJobSpec(Optional<String> tenantId, String name) {
    return Optional.ofNullable(cronJobSpecs.get(scopedKey(tenantId, name)));
  }

  public List<CronJobSpec> listCronJobSpecs() {
    return List.copyOf(cronJobSpecs.values());
  }

  /**
   * Also clears any recorded {@link #cronJobLastSchedule} for {@code name} -- a removed CronJob has
   * no schedule state left to advance. Does not touch any {@link JobSpec} it previously generated;
   * those are ordinary Jobs now, left to run to completion (or be cleaned up by an operator)
   * independently, the same way undeploying a Deployment never retroactively touches instances a
   * since-removed autoscale policy once sized.
   */
  public void removeCronJobSpec(Optional<String> tenantId, String name) {
    String key = scopedKey(tenantId, name);
    cronJobSpecs.remove(key);
    cronJobLastSchedule.remove(key);
  }

  // ---- cronjob last-schedule bookkeeping ----

  public void putCronJobLastSchedule(
      Optional<String> tenantId, String name, Instant lastScheduleTime) {
    cronJobLastSchedule.put(scopedKey(tenantId, name), lastScheduleTime);
  }

  /** Empty means "never fired yet" -- see {@link #cronJobLastSchedule}'s own field javadoc. */
  public Optional<Instant> getCronJobLastSchedule(Optional<String> tenantId, String name) {
    return Optional.ofNullable(cronJobLastSchedule.get(scopedKey(tenantId, name)));
  }

  // ---- daemonsets ----

  public void putDaemonSetSpec(DaemonSetSpec spec) {
    daemonSetSpecs.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<DaemonSetSpec> getDaemonSetSpec(Optional<String> tenantId, String name) {
    return Optional.ofNullable(daemonSetSpecs.get(scopedKey(tenantId, name)));
  }

  public List<DaemonSetSpec> listDaemonSetSpecs() {
    return List.copyOf(daemonSetSpecs.values());
  }

  /**
   * Also clears any in-flight {@link #rollingDaemonSetNodes} entry for {@code name} -- a removed
   * DaemonSet has no rollout left to track. Does not remove any {@link DaemonSetAssignment} still
   * on disk for it; {@code DaemonSetReconciler}'s own orphaned-assignment sweep is responsible for
   * those, the same "spec gone -> reconciler tears assignments down" ordering {@link
   * #removeDeployment} already relies on for {@link InstanceAssignment}. Also clears this name's
   * {@link ControllerRevision} history and its instances' {@link InstanceEvent} timelines, for the
   * same delete-then-recreate reason {@link #removeDeployment}'s own javadoc gives.
   */
  public void removeDaemonSetSpec(Optional<String> tenantId, String name) {
    daemonSetSpecs.remove(scopedKey(tenantId, name));
    clearAllRollingDaemonSetNodes(tenantId, name);
    daemonSetDesiredCounts.remove(scopedKey(tenantId, name));
    controllerRevisions.remove(ControllerRevision.revisionKey("DaemonSet", tenantId, name));
    clearInstanceEventsFor(tenantId, name);
  }

  // ---- daemonset assignments ----

  public void putDaemonSetAssignment(DaemonSetAssignment assignment) {
    daemonSetAssignments.put(
        daemonSetAssignmentKey(
            assignment.tenantId(), assignment.daemonSetName(), assignment.nodeId()),
        assignment);
  }

  public void removeDaemonSetAssignment(
      Optional<String> tenantId, String daemonSetName, String nodeId) {
    daemonSetAssignments.remove(daemonSetAssignmentKey(tenantId, daemonSetName, nodeId));
  }

  public List<DaemonSetAssignment> listDaemonSetAssignments() {
    return List.copyOf(daemonSetAssignments.values());
  }

  public List<DaemonSetAssignment> listDaemonSetAssignmentsFor(
      Optional<String> tenantId, String daemonSetName) {
    return daemonSetAssignments.values().stream()
        .filter(a -> a.tenantId().equals(tenantId) && a.daemonSetName().equals(daemonSetName))
        .toList();
  }

  // ---- daemonset rolling-update bookkeeping ----

  /**
   * Marks {@code nodeId} as one of the (possibly several, bounded by the DaemonSet's own effective
   * {@code maxUnavailable}) nodes currently being migrated by a rolling update -- the node-keyed
   * counterpart to {@link #addRollingIndex}, persisted the identical way and for the identical
   * reason: a reconciler restart mid-rollout resumes rather than starting a second one. One file
   * per {@code (daemonSetName, nodeId)} pair, mirroring {@link #daemonSetAssignmentFile}'s own
   * per-node layout under a per-daemonset subdirectory, rather than one file holding every
   * in-flight node -- an add/remove is then always a single small file write/delete, never a
   * read-modify-write of a shared file.
   */
  public void addRollingDaemonSetNode(
      Optional<String> tenantId, String daemonSetName, String nodeId) {
    rollingDaemonSetNodes
        .computeIfAbsent(scopedKey(tenantId, daemonSetName), key -> ConcurrentHashMap.newKeySet())
        .add(nodeId);
  }

  public void removeRollingDaemonSetNode(
      Optional<String> tenantId, String daemonSetName, String nodeId) {
    Set<String> nodes = rollingDaemonSetNodes.get(scopedKey(tenantId, daemonSetName));
    if (nodes != null) {
      nodes.remove(nodeId);
    }
  }

  /** Every node currently in flight for this DaemonSet's rollout; empty means none. */
  public Set<String> getRollingDaemonSetNodes(Optional<String> tenantId, String daemonSetName) {
    return Set.copyOf(
        rollingDaemonSetNodes.getOrDefault(scopedKey(tenantId, daemonSetName), Set.of()));
  }

  private void clearAllRollingDaemonSetNodes(Optional<String> tenantId, String daemonSetName) {
    Set.copyOf(rollingDaemonSetNodes.getOrDefault(scopedKey(tenantId, daemonSetName), Set.of()))
        .forEach(nodeId -> removeRollingDaemonSetNode(tenantId, daemonSetName, nodeId));
  }

  // ---- daemonset desired-count bookkeeping ----

  /**
   * Set by {@code DaemonSetReconciler} every tick to the count of nodes it found eligible via
   * {@code Scheduler#eligibleNodes} -- the DaemonSet analogue of {@link DeploymentSpec#replicas()},
   * recomputed rather than read off the spec since a DaemonSet's desired count depends on live node
   * state.
   */
  public void putDaemonSetDesiredCount(
      Optional<String> tenantId, String daemonSetName, int desiredCount) {
    daemonSetDesiredCounts.put(scopedKey(tenantId, daemonSetName), desiredCount);
  }

  /** Empty until the reconciler's first tick for this daemonset. */
  public Optional<Integer> getDaemonSetDesiredCount(
      Optional<String> tenantId, String daemonSetName) {
    return Optional.ofNullable(daemonSetDesiredCounts.get(scopedKey(tenantId, daemonSetName)));
  }

  // ---- statefulsets ----

  public void putStatefulSetSpec(StatefulSetSpec spec) {
    statefulSetSpecs.put(scopedKey(spec.tenantId(), spec.name()), spec);
  }

  public Optional<StatefulSetSpec> getStatefulSetSpec(Optional<String> tenantId, String name) {
    return Optional.ofNullable(statefulSetSpecs.get(scopedKey(tenantId, name)));
  }

  public List<StatefulSetSpec> listStatefulSetSpecs() {
    return List.copyOf(statefulSetSpecs.values());
  }

  /**
   * Also clears any in-flight {@link #rollingStatefulSetIndices} entry for {@code name} -- a
   * removed StatefulSet has no rollout left to track. Deliberately does <em>not</em> clear any
   * {@link #statefulSetIndexNodes} sticky bindings here -- those are cleared per-index by {@link
   * #removeStatefulSetIndexNode} only when the reconciler actually tears an index down for good
   * (this method removing the spec doesn't itself remove any {@link StatefulSetAssignment}; {@code
   * StatefulSetReconciler}'s own orphaned-assignment sweep does that, the same "spec gone ->
   * reconciler tears assignments down" ordering {@link #removeDeployment} already relies on). Also
   * clears this name's {@link ControllerRevision} history and its instances' {@link InstanceEvent}
   * timelines, for the same delete-then-recreate reason {@link #removeDeployment}'s own javadoc
   * gives.
   */
  public void removeStatefulSetSpec(Optional<String> tenantId, String name) {
    statefulSetSpecs.remove(scopedKey(tenantId, name));
    clearRollingStatefulSetIndex(tenantId, name);
    controllerRevisions.remove(ControllerRevision.revisionKey("StatefulSet", tenantId, name));
    clearInstanceEventsFor(tenantId, name);
  }

  // ---- statefulset assignments ----

  public void putStatefulSetAssignment(StatefulSetAssignment assignment) {
    statefulSetAssignments.put(
        statefulSetAssignmentKey(
            assignment.tenantId(), assignment.statefulSetName(), assignment.instanceIndex()),
        assignment);
  }

  public void removeStatefulSetAssignment(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    statefulSetAssignments.remove(
        statefulSetAssignmentKey(tenantId, statefulSetName, instanceIndex));
  }

  public List<StatefulSetAssignment> listStatefulSetAssignments() {
    return List.copyOf(statefulSetAssignments.values());
  }

  public List<StatefulSetAssignment> listStatefulSetAssignmentsFor(
      Optional<String> tenantId, String statefulSetName) {
    return statefulSetAssignments.values().stream()
        .filter(a -> a.tenantId().equals(tenantId) && a.statefulSetName().equals(statefulSetName))
        .toList();
  }

  // ---- statefulset rolling-update / OrderedReady bookkeeping ----

  /**
   * The single index currently in flight for a StatefulSet, if any -- see {@link
   * StateMutation.PutRollingStatefulSetIndex}'s own javadoc for why this one marker governs both
   * ordinary {@code OrderedReady} scale-up admission and rolling-update admission.
   */
  public void putRollingStatefulSetIndex(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    rollingStatefulSetIndices.put(scopedKey(tenantId, statefulSetName), instanceIndex);
  }

  public void clearRollingStatefulSetIndex(Optional<String> tenantId, String statefulSetName) {
    rollingStatefulSetIndices.remove(scopedKey(tenantId, statefulSetName));
  }

  public Optional<Integer> getRollingStatefulSetIndex(
      Optional<String> tenantId, String statefulSetName) {
    return Optional.ofNullable(rollingStatefulSetIndices.get(scopedKey(tenantId, statefulSetName)));
  }

  // ---- statefulset sticky node-binding bookkeeping ----

  /**
   * See {@link StateMutation.PutStatefulSetIndexNode}'s own javadoc: written once, the first time
   * an index is ever placed, read back by every later placement attempt for that same index as
   * {@code Scheduler}'s {@code stickyNodeId} input, and left untouched by an ordinary assignment
   * removal.
   */
  public void putStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex, String nodeId) {
    statefulSetIndexNodes.put(
        statefulSetAssignmentKey(tenantId, statefulSetName, instanceIndex), nodeId);
  }

  /** Fired only on genuinely permanent index removal -- see the field's own javadoc. */
  public void removeStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    statefulSetIndexNodes.remove(
        statefulSetAssignmentKey(tenantId, statefulSetName, instanceIndex));
  }

  public Optional<String> getStatefulSetIndexNode(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    return Optional.ofNullable(
        statefulSetIndexNodes.get(
            statefulSetAssignmentKey(tenantId, statefulSetName, instanceIndex)));
  }

  // ---- rolling-update bookkeeping ----

  /**
   * Marks {@code instanceIndex} as one of the (possibly several, bounded by the deployment's own
   * effective {@link com.gimle.mimir.manifest.DisruptionBudget#maxUnavailable}) indices currently
   * being replaced by a rolling update -- persisted so a reconciler restart mid-rollout resumes
   * rather than starting a second one. One file per {@code (deploymentName, instanceIndex)} pair,
   * mirroring {@link #assignmentFile}'s own per-index layout under a per-deployment subdirectory,
   * rather than one file holding every in-flight index -- an add/remove is then always a single
   * small file write/delete, never a read-modify-write of a shared file.
   */
  public void addRollingIndex(Optional<String> tenantId, String deploymentName, int instanceIndex) {
    rollingIndices
        .computeIfAbsent(scopedKey(tenantId, deploymentName), key -> ConcurrentHashMap.newKeySet())
        .add(instanceIndex);
  }

  public void removeRollingIndex(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    Set<Integer> indices = rollingIndices.get(scopedKey(tenantId, deploymentName));
    if (indices != null) {
      indices.remove(instanceIndex);
    }
  }

  /** Every instance index currently in flight for this deployment's rollout; empty means none. */
  public Set<Integer> getRollingIndices(Optional<String> tenantId, String deploymentName) {
    return Set.copyOf(rollingIndices.getOrDefault(scopedKey(tenantId, deploymentName), Set.of()));
  }

  private void clearAllRollingIndices(Optional<String> tenantId, String deploymentName) {
    Set.copyOf(rollingIndices.getOrDefault(scopedKey(tenantId, deploymentName), Set.of()))
        .forEach(index -> removeRollingIndex(tenantId, deploymentName, index));
  }

  // ---- surge bookkeeping ----

  /**
   * Marks {@code surgeIndex} (a synthetic index {@code >= replicas}) as provisioning a replacement
   * for {@code targetIndex} ahead of removing the original -- the surge counterpart to {@link
   * #addRollingIndex}, persisted the identical way and for the identical reason: a reconciler
   * restart mid-surge resumes tracking the promotion rather than losing it. One file per {@code
   * (deploymentName, surgeIndex)} pair, the same per-pair-file layout {@link #addRollingIndex}
   * already established, under its own subdirectory so the two bookkeeping kinds never collide on a
   * shared index number.
   */
  public void addSurgeIndex(
      Optional<String> tenantId, String deploymentName, int surgeIndex, int targetIndex) {
    surgeIndices
        .computeIfAbsent(scopedKey(tenantId, deploymentName), key -> new ConcurrentHashMap<>())
        .put(surgeIndex, targetIndex);
  }

  public void removeSurgeIndex(Optional<String> tenantId, String deploymentName, int surgeIndex) {
    Map<Integer, Integer> indices = surgeIndices.get(scopedKey(tenantId, deploymentName));
    if (indices != null) {
      indices.remove(surgeIndex);
    }
  }

  /**
   * Every surge slot currently in flight for this deployment, keyed by surgeIndex; empty means
   * none.
   */
  public Map<Integer, Integer> getSurgeIndices(Optional<String> tenantId, String deploymentName) {
    return Map.copyOf(surgeIndices.getOrDefault(scopedKey(tenantId, deploymentName), Map.of()));
  }

  private void clearAllSurgeIndices(Optional<String> tenantId, String deploymentName) {
    Set.copyOf(surgeIndices.getOrDefault(scopedKey(tenantId, deploymentName), Map.of()).keySet())
        .forEach(surgeIndex -> removeSurgeIndex(tenantId, deploymentName, surgeIndex));
  }

  // ---- autoscaling bookkeeping ----

  /**
   * The autoscaler's current target replica count, read by {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler} in place of {@code
   * DeploymentSpec#replicas()} whenever a deployment carries an {@code autoscale} policy.
   */
  public void putEffectiveReplicas(Optional<String> tenantId, String deploymentName, int replicas) {
    effectiveReplicas.put(scopedKey(tenantId, deploymentName), replicas);
  }

  public Optional<Integer> getEffectiveReplicas(Optional<String> tenantId, String deploymentName) {
    return Optional.ofNullable(effectiveReplicas.get(scopedKey(tenantId, deploymentName)));
  }

  /**
   * Stamped by {@code AutoscaleReconciler} in the same batch as the {@code effectiveReplicas} move
   * it accounts for, so the two can never disagree about whether a scale event happened.
   */
  public void putDeploymentLastScale(
      Optional<String> tenantId, String deploymentName, Instant lastScaleTime) {
    deploymentLastScale.put(scopedKey(tenantId, deploymentName), lastScaleTime);
  }

  /** Empty means "never scaled" -- see {@link #deploymentLastScale}'s own field comment. */
  public Optional<Instant> getDeploymentLastScale(
      Optional<String> tenantId, String deploymentName) {
    return Optional.ofNullable(deploymentLastScale.get(scopedKey(tenantId, deploymentName)));
  }

  // ---- node registrations ----

  public void putNodeRegistration(NodeRegistration registration) {
    nodeRegistrations.put(registration.nodeId(), registration);
  }

  public Optional<NodeRegistration> getNodeRegistration(String nodeId) {
    return Optional.ofNullable(nodeRegistrations.get(nodeId));
  }

  public List<NodeRegistration> listNodeRegistrations() {
    return List.copyOf(nodeRegistrations.values());
  }

  /**
   * Not read by any reconciler today (a node re-registers on restart rather than being explicitly
   * deregistered), but required by {@link #restoreFromSnapshot}: installing a snapshot must be able
   * to wipe every registration this replica previously knew about before repopulating from the
   * snapshot's own set, the same way every other resource kind here can.
   */
  public void removeNodeRegistration(String nodeId) {
    nodeRegistrations.remove(nodeId);
  }

  // ---- node heartbeats ----

  /**
   * Leader-local only -- heartbeats never enter the replicated Raft log (too high-frequency, and
   * tolerant of a brief gap after a leader change), so only whichever replica is currently leader
   * ever has a given node's heartbeat in its own {@code nodeHeartbeats} map. {@link
   * com.gimle.mimir.rpc.StoreNode}/{@code StoreClient} route both {@code PutHeartbeat} and {@code
   * GetNodeHeartbeat} through the current leader specifically for this reason -- a follower's own
   * copy here is never anything but empty, not merely stale.
   */
  public void putNodeHeartbeat(NodeHeartbeat heartbeat) {
    Instant receivedAt = clock.instant();
    nodeHeartbeats.put(heartbeat.nodeId(), new ObservedHeartbeat(heartbeat, receivedAt));
  }

  /** See {@link #putNodeHeartbeat}'s own javadoc for why this is leader-local. */
  public Optional<ObservedHeartbeat> getNodeHeartbeat(String nodeId) {
    return Optional.ofNullable(nodeHeartbeats.get(nodeId));
  }

  public List<ObservedHeartbeat> listNodeHeartbeats() {
    return List.copyOf(nodeHeartbeats.values());
  }

  // ---- leases ----

  /**
   * Non-replicated, leader-local coordination state -- same category as {@code nodeHeartbeats}
   * above, not {@code StateMutation}-backed and excluded from {@link #snapshot}/{@link
   * #restoreFromSnapshot} for the identical reason. Backs the reconciler-leader election among
   * {@code ApiServer} replicas once they're decoupled from the store's own Raft membership -- the
   * same shape Kubernetes' own {@code coordination.k8s.io/v1 Lease} serves for {@code
   * kube-controller-manager}/{@code kube-scheduler} elections, just held in memory rather than
   * replicated, since losing an uncommitted lease on a leader failover only costs one election
   * cycle, not correctness.
   *
   * <p>Grants {@code holderId} the lease if it's free, expired, or already held by {@code holderId}
   * (a renewal); denies it otherwise, returning the current holder so a denied caller can log who
   * won without a second round trip.
   */
  public LeaseGrant tryAcquireOrRenewLease(String name, String holderId, Duration ttl) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    LeaseState granted = new LeaseState(holderId, expiresAt);
    LeaseState[] result = new LeaseState[1];
    leases.compute(
        name,
        (key, current) -> {
          if (current == null
              || current.expiresAt().isBefore(now)
              || current.holderId().equals(holderId)) {
            result[0] = granted;
            return granted;
          }
          result[0] = current;
          return current;
        });
    return new LeaseGrant(result[0] == granted, result[0].holderId(), result[0].expiresAt());
  }

  /** No-op if {@code holderId} doesn't currently hold {@code name} (already lost or never held). */
  public void releaseLease(String name, String holderId) {
    leases.computeIfPresent(
        name, (key, current) -> current.holderId().equals(holderId) ? null : current);
  }

  /** Empty if the lease is free or its current holder's grant has expired. */
  public Optional<String> getLeaseHolder(String name) {
    LeaseState current = leases.get(name);
    if (current == null || current.expiresAt().isBefore(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(current.holderId());
  }

  private record LeaseState(String holderId, Instant expiresAt) {}

  // ---- tenants ----

  public void putTenant(Tenant tenant) {
    tenants.put(tenant.id(), tenant);
  }

  public Optional<Tenant> getTenant(String id) {
    return Optional.ofNullable(tenants.get(id));
  }

  public List<Tenant> listTenants() {
    return List.copyOf(tenants.values());
  }

  public void removeTenant(String id) {
    tenants.remove(id);
  }

  // ---- quota-violation bookkeeping ----

  /**
   * Set by {@code QuotaReconciler} every tick, read by the API server's deployment status surface
   * -- a level-triggered flag, not an event, so a deployment whose tenant's quota is retroactively
   * raised again clears automatically on the next tick without any special-cased "resolved" path.
   */
  public void putQuotaViolation(
      Optional<String> tenantId, String deploymentName, boolean violating) {
    String key = scopedKey(tenantId, deploymentName);
    if (!violating) {
      quotaViolations.remove(key);
      return;
    }
    quotaViolations.put(key, Boolean.TRUE);
  }

  public boolean isQuotaViolating(Optional<String> tenantId, String deploymentName) {
    return quotaViolations.getOrDefault(scopedKey(tenantId, deploymentName), Boolean.FALSE);
  }

  // ---- node cordon bookkeeping ----

  /**
   * Operator-set "don't place anything new here" flag, read by {@code Scheduler}'s cordon filter
   * stage -- never evicts what's already running on {@code nodeId}, only excludes it from future
   * placement. Same level-triggered, remove-the-file-when-false shape as {@link
   * #putQuotaViolation}.
   */
  public void putNodeCordon(String nodeId, boolean cordoned) {
    if (!cordoned) {
      nodeCordons.remove(nodeId);
      return;
    }
    nodeCordons.put(nodeId, Boolean.TRUE);
  }

  public boolean isNodeCordoned(String nodeId) {
    return nodeCordons.getOrDefault(nodeId, Boolean.FALSE);
  }

  // ---- node taint bookkeeping ----

  /**
   * Operator-set "this node is reserved for these tenants only" flag, read by {@code Scheduler}'s
   * taint filter stage -- an empty set (the default, for every node no operator has ever tainted)
   * means the node is open to any tenant, matching a fresh Kubernetes cluster's own default of no
   * built-in tenant isolation. A non-empty set excludes every candidate whose own {@code tenantId}
   * isn't a member of it, replacing the old blanket "no other tenant may already be present"
   * co-residency filter with a real, deliberately-declared boundary that doesn't degrade as the
   * number of distinct tenants on a node grows. Same present-means-added shape as {@link
   * #putNodeCordon}, except keyed by (nodeId, tenantId) pair rather than a single boolean, since
   * one node may be dedicated to more than one tenant.
   */
  public void putNodeTaint(String nodeId, String tenantId, boolean tainted) {
    if (!tainted) {
      Set<String> existing = nodeTaints.get(nodeId);
      if (existing != null) {
        existing.remove(tenantId);
        if (existing.isEmpty()) {
          nodeTaints.remove(nodeId);
        }
      }
      return;
    }
    nodeTaints.computeIfAbsent(nodeId, key -> ConcurrentHashMap.newKeySet()).add(tenantId);
  }

  public Set<String> getNodeTaints(String nodeId) {
    return Set.copyOf(nodeTaints.getOrDefault(nodeId, Set.of()));
  }

  // ---- certificate revocation bookkeeping ----

  /**
   * Marks (or clears) one issued certificate's serial number as revoked -- checked by every process
   * that authenticates peer certificates, before any authorization runs. Same present-means-true
   * shape as {@link #putNodeCordon}: a cleared serial is removed outright, so the map only ever
   * holds currently-revoked serials.
   */
  public void putCertificateRevocation(String serialNumber, boolean revoked) {
    if (!revoked) {
      revokedCertificateSerials.remove(serialNumber);
      return;
    }
    revokedCertificateSerials.put(serialNumber, Boolean.TRUE);
  }

  public boolean isCertificateRevoked(String serialNumber) {
    return revokedCertificateSerials.getOrDefault(serialNumber, Boolean.FALSE);
  }

  public Set<String> listRevokedCertificateSerials() {
    return Set.copyOf(revokedCertificateSerials.keySet());
  }

  // ---- secrets master key retirement bookkeeping ----

  /**
   * Marks (or clears) one Fafnir secrets master key id as retired -- the identical
   * present-means-true shape {@link #putCertificateRevocation} already uses for a different kind of
   * compromised credential, checked by every Fafnir replica's own {@code FafnirCrypto#decrypt}
   * before attempting to decrypt anything, so retirement actually takes effect cluster-wide rather
   * than only on whichever single replica processed the {@code retire-key} call.
   */
  public void putSecretsKeyRetirement(byte keyId, boolean retired) {
    if (!retired) {
      retiredSecretsKeyIds.remove(keyId);
      return;
    }
    retiredSecretsKeyIds.put(keyId, Boolean.TRUE);
  }

  public boolean isSecretsKeyRetired(byte keyId) {
    return retiredSecretsKeyIds.getOrDefault(keyId, Boolean.FALSE);
  }

  public Set<Byte> listRetiredSecretsKeyIds() {
    return Set.copyOf(retiredSecretsKeyIds.keySet());
  }

  // ---- console session revocation bookkeeping ----

  /**
   * Advances {@code username}'s "revoked before" watermark to {@code revokedBeforeEpochMilli} --
   * called on every {@code /auth/logout} for whichever username the presented cookie verified to. A
   * session token is otherwise a fully stateless, self-verifying HMAC token (see {@code
   * SessionTokens}' own javadoc); this is the one piece of server-side state layered on top of it,
   * mirroring {@link #putCertificateRevocation} for a different credential type -- checked before
   * any authorization runs, the same per-request level-triggered store read.
   *
   * <p>Merges with {@link Math#max} rather than a plain {@code put}: two logouts for the same
   * username can replay in either order relative to their own wall-clock stamps (clock skew, or a
   * mutation submitted earlier that commits later), and the watermark must only ever ratchet
   * forward -- never let a stale, lower stamp silently undo an already-applied revocation.
   */
  public void putSessionRevocation(String username, long revokedBeforeEpochMilli) {
    sessionRevokedBeforeEpochMilli.merge(username, revokedBeforeEpochMilli, Math::max);
  }

  /** {@code 0} (never revoked) for a username that has never logged out. */
  public long getSessionRevokedBeforeEpochMilli(String username) {
    return sessionRevokedBeforeEpochMilli.getOrDefault(username, 0L);
  }

  // ---- workload-identity token bookkeeping ----

  /**
   * Replaces {@code record.key()}'s live token and opportunistically drops every entry already
   * expired as of {@code mintedAtEpochMilli} -- the mutation's own stamp, never this replica's
   * clock, so the sweep is deterministic across replicas and replays (see {@code
   * StateMutation.PutWorkloadToken}).
   */
  public void putWorkloadToken(WorkloadTokenRecord record, long mintedAtEpochMilli) {
    workloadTokens
        .values()
        .removeIf(existing -> existing.expiresAtEpochMilli() < mintedAtEpochMilli);
    workloadTokens.put(record.key(), record);
  }

  public void removeWorkloadToken(String key) {
    workloadTokens.remove(key);
  }

  public Optional<WorkloadTokenRecord> getWorkloadToken(String key) {
    return Optional.ofNullable(workloadTokens.get(key));
  }

  // ---- tenant-scoped config/secrets ----

  public void putConfigEntry(ConfigEntry entry) {
    String key = configKey(entry.tenantId(), entry.key());
    configEntries.put(key, entry);
  }

  public Optional<ConfigEntry> getConfigEntry(String tenantId, String key) {
    return Optional.ofNullable(configEntries.get(configKey(tenantId, key)));
  }

  public List<ConfigEntry> listConfigEntriesFor(String tenantId) {
    return configEntries.values().stream().filter(e -> e.tenantId().equals(tenantId)).toList();
  }

  public void removeConfigEntry(String tenantId, String key) {
    configEntries.remove(configKey(tenantId, key));
  }

  // ---- roles ----

  public void putRole(Role role) {
    roles.put(role.name(), role);
  }

  public Optional<Role> getRole(String name) {
    return Optional.ofNullable(roles.get(name));
  }

  public List<Role> listRoles() {
    return List.copyOf(roles.values());
  }

  public void removeRole(String name) {
    roles.remove(name);
  }

  /**
   * Removes every {@link RoleBinding} whose {@code roleName} equals {@code name}, returning the
   * ones removed. Called from {@code StateMutation.RemoveRole}'s own {@code applyTo} so a Role's
   * deletion and its bindings' cleanup commit as a single Raft log entry -- {@code
   * RoleBinding.roleName} is a plain string resolved by name at authorize-time, not an immutable
   * ID, so a binding left behind after its Role is deleted sits inert only until someone later
   * {@code PUT}s a <i>new</i> Role under the same name, at which point it silently reactivates with
   * whatever permissions that new Role grants. Doing this here, inside the mutation that deletes
   * the Role, rather than as a separate proposal from the caller, means no window exists where the
   * Role is gone but a stale binding naming it still is not.
   */
  public List<RoleBinding> removeRoleBindingsForRole(String name) {
    List<RoleBinding> removed = new ArrayList<>();
    roleBindings
        .values()
        .removeIf(
            binding -> {
              if (!binding.roleName().equals(name)) {
                return false;
              }
              removed.add(binding);
              return true;
            });
    return List.copyOf(removed);
  }

  // ---- role bindings ----

  public void putRoleBinding(RoleBinding binding) {
    roleBindings.put(binding.id(), binding);
  }

  public Optional<RoleBinding> getRoleBinding(String id) {
    return Optional.ofNullable(roleBindings.get(id));
  }

  public List<RoleBinding> listRoleBindings() {
    return List.copyOf(roleBindings.values());
  }

  public void removeRoleBinding(String id) {
    roleBindings.remove(id);
  }

  // ---- accounts ----

  /**
   * Console-login-only, see {@link Account}'s own javadoc. {@link #listAccounts()} being empty is
   * exactly the signal {@code ApiServer} checks before seeding a bootstrap account from {@code
   * gimle-pki}'s {@code bootstrap-account.yaml} -- never re-seeded once any account exists.
   */
  public void putAccount(Account account) {
    accounts.put(account.username(), account);
  }

  public Optional<Account> getAccount(String username) {
    return Optional.ofNullable(accounts.get(username));
  }

  public List<Account> listAccounts() {
    return List.copyOf(accounts.values());
  }

  public void removeAccount(String username) {
    accounts.remove(username);
  }

  // ---- reconciler backoff/grace-period bookkeeping ----

  /**
   * Written by {@code HealthReconciler} and {@code ReplicaCountReconciler} alike -- see {@link
   * ReconcilerInstanceState}'s own javadoc for why both share one resource kind.
   */
  public void putReconcilerInstanceState(ReconcilerInstanceState state) {
    reconcilerInstanceStates.put(
        reconcilerStateKey(state.tenantId(), state.deploymentName(), state.instanceIndex()), state);
  }

  public Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return Optional.ofNullable(
        reconcilerInstanceStates.get(reconcilerStateKey(tenantId, deploymentName, instanceIndex)));
  }

  public void removeReconcilerInstanceState(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    reconcilerInstanceStates.remove(reconcilerStateKey(tenantId, deploymentName, instanceIndex));
  }

  public List<ReconcilerInstanceState> listReconcilerInstanceStates() {
    return List.copyOf(reconcilerInstanceStates.values());
  }

  /**
   * The {@link WorkloadHealthState} equivalent of {@link #putReconcilerInstanceState} for a
   * StatefulSet index or DaemonSet node-instance -- see that record's own javadoc for why it's a
   * separate resource kind rather than reusing {@link ReconcilerInstanceState}.
   */
  public void putWorkloadHealthState(WorkloadHealthState state) {
    workloadHealthStates.put(
        workloadHealthKey(
            state.tenantId(), state.workloadKind(), state.workloadName(), state.slot()),
        state);
  }

  public Optional<WorkloadHealthState> getWorkloadHealthState(
      Optional<String> tenantId, String workloadKind, String workloadName, String slot) {
    return Optional.ofNullable(
        workloadHealthStates.get(workloadHealthKey(tenantId, workloadKind, workloadName, slot)));
  }

  public void removeWorkloadHealthState(
      Optional<String> tenantId, String workloadKind, String workloadName, String slot) {
    workloadHealthStates.remove(workloadHealthKey(tenantId, workloadKind, workloadName, slot));
  }

  public List<WorkloadHealthState> listWorkloadHealthStates() {
    return List.copyOf(workloadHealthStates.values());
  }

  // ---- per-instance lifecycle event log ----

  /**
   * Appends one event to {@code event.deploymentName()}/{@code event.instanceIndex()}'s timeline,
   * pruning the oldest event(s) once {@link #MAX_EVENTS_PER_INSTANCE} is exceeded -- a pure
   * function of the event and what's already stored, so every Raft replica applying the same
   * committed {@code AppendInstanceEvent} entries in the same order ends up with identical pruning
   * decisions, not a separate non-replicated cleanup pass.
   */
  public void putInstanceEvent(Optional<String> tenantId, InstanceEvent event) {
    String key = instanceEventsKey(tenantId, event.deploymentName(), event.instanceIndex());
    instanceEvents.compute(
        key,
        (k, existing) -> {
          List<InstanceEvent> updated = new ArrayList<>(existing == null ? List.of() : existing);
          updated.add(event);
          while (updated.size() > MAX_EVENTS_PER_INSTANCE) {
            InstanceEvent oldest = updated.remove(0);
          }
          return List.copyOf(updated);
        });
  }

  /**
   * Drops every instance index's timeline for one workload name, called from each workload kind's
   * own removal so a later recreate under the same name starts with an empty timeline rather than
   * the deleted workload's history. {@link #instanceEventsKey} appends {@code '#'} plus the
   * instance index to the scoped key, so the match is on that prefix with an all-digits remainder
   * -- a plain {@code startsWith} would also sweep an unrelated workload whose own name begins with
   * this one followed by a {@code '#'}.
   */
  private void clearInstanceEventsFor(Optional<String> tenantId, String workloadName) {
    String prefix = scopedKey(tenantId, workloadName) + "#";
    instanceEvents.keySet().removeIf(key -> isInstanceIndexUnder(key, prefix));
  }

  private static boolean isInstanceIndexUnder(String key, String prefix) {
    if (!key.startsWith(prefix)) {
      return false;
    }
    String suffix = key.substring(prefix.length());
    return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
  }

  /** Newest-first -- the natural read order for a timeline, matching {@code /logs}' own tail. */
  public List<InstanceEvent> listInstanceEvents(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    List<InstanceEvent> events =
        instanceEvents.getOrDefault(
            instanceEventsKey(tenantId, deploymentName, instanceIndex), List.of());
    List<InstanceEvent> reversed = new ArrayList<>(events);
    Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  /**
   * Cluster-wide, newest-first across every instance's own timeline at once -- the read side of
   * {@link #putInstanceEvent} un-scoped to one instance, for "what has this cluster been doing"
   * rather than one instance's own history. An absent {@code tenantId} matches every tenant's
   * timelines (the untenanted namespace included); a present one narrows to exactly that tenant's
   * own timelines, the same scoping {@link #instanceEventsKey} already keys them under -- unlike
   * {@link #listInstanceEvents(Optional, String, int)}'s own single-instance convention, where an
   * absent tenant addresses the untenanted namespace specifically rather than everything. An absent
   * {@code since} matches every retained event; a present one is an inclusive lower bound, the same
   * filter-after-retrieve shape {@link #listAuditEvents} already uses. Ties in {@code
   * occurredAtEpochMilli} break on {@code id} so the merged order is fully deterministic regardless
   * of the per-instance maps' own iteration order -- required for cursor pagination to see a stable
   * sequence across repeated calls.
   */
  public List<InstanceEvent> listInstanceEvents(Optional<String> tenantId, Optional<Long> since) {
    List<InstanceEvent> matching = new ArrayList<>();
    for (Map.Entry<String, List<InstanceEvent>> entry : instanceEvents.entrySet()) {
      if (tenantId.isPresent()
          && !tenantId.get().equals(tenantOfInstanceEventsKey(entry.getKey()))) {
        continue;
      }
      matching.addAll(entry.getValue());
    }
    return matching.stream()
        .filter(e -> since.isEmpty() || e.occurredAtEpochMilli() >= since.get())
        .sorted(
            Comparator.comparingLong(InstanceEvent::occurredAtEpochMilli)
                .reversed()
                .thenComparing(InstanceEvent::id))
        .toList();
  }

  /** The tenant segment {@link #scopedKey} prepends to an {@link #instanceEventsKey}. */
  private static String tenantOfInstanceEventsKey(String key) {
    int separator = key.indexOf('\0');
    return separator < 0 ? "" : key.substring(0, separator);
  }

  // ---- cross-resource audit trail ----

  /**
   * Appends one decision to the cluster-wide audit trail, pruning the oldest event(s) once {@link
   * #MAX_AUDIT_EVENTS} is exceeded -- the append and the prune happen inside one {@code
   * synchronized} block so every Raft replica applying the same committed {@code AppendAuditEvent}
   * entries in the same order ends up with identical pruning decisions, the same guarantee {@link
   * #putInstanceEvent}'s per-key {@code compute} gives it for free.
   */
  public void putAuditEvent(AuditEvent event) {
    synchronized (auditEventsLock) {
      auditEvents.add(event);
      while (auditEvents.size() > MAX_AUDIT_EVENTS) {
        auditEvents.remove(0);
        auditEventsEvictedTotal++;
        if (auditEventsEvictedTotal == 1
            || auditEventsEvictedTotal % AUDIT_EVICTION_LOG_INTERVAL == 0) {
          log.warn(
              "audit trail exceeded its {}-event cap; oldest events are being discarded "
                  + "({} discarded so far)",
              MAX_AUDIT_EVENTS,
              auditEventsEvictedTotal);
        }
      }
    }
  }

  /**
   * The trail's own retention state, independent of any {@link #listAuditEvents} filter -- see
   * {@link AuditTrailStatus}'s own javadoc for why this rides alongside every {@code GET /audit}
   * response rather than only ever showing up as a log line.
   */
  public AuditTrailStatus auditTrailStatus() {
    synchronized (auditEventsLock) {
      Optional<Long> oldestRetainedAtEpochMilli =
          auditEvents.isEmpty()
              ? Optional.empty()
              : Optional.of(auditEvents.get(0).occurredAtEpochMilli());
      return new AuditTrailStatus(
          auditEvents.size(), auditEventsEvictedTotal, oldestRetainedAtEpochMilli);
    }
  }

  /**
   * Newest-first, with every filter applied in-memory over the retained window -- the same
   * filter-after-retrieve shape every other {@code StoreReader} list method already uses. An empty
   * {@code Optional} filter matches everything for that dimension.
   */
  public List<AuditEvent> listAuditEvents(
      Optional<String> principal,
      Optional<String> resourceKind,
      Optional<String> tenantId,
      Optional<Long> since) {
    List<AuditEvent> snapshot;
    synchronized (auditEventsLock) {
      snapshot = new ArrayList<>(auditEvents);
    }
    Collections.reverse(snapshot);
    return snapshot.stream()
        .filter(e -> principal.isEmpty() || principal.get().equals(e.principal()))
        .filter(e -> resourceKind.isEmpty() || resourceKind.get().equals(e.resourceKind()))
        .filter(e -> tenantId.isEmpty() || e.tenantId().equals(tenantId))
        .filter(e -> since.isEmpty() || e.occurredAtEpochMilli() >= since.get())
        .toList();
  }

  // ---- controller revision history ----

  /**
   * Appends one revision to {@code (revision.workloadKind(), revision.name())}'s history, pruning
   * the oldest revision(s) once {@link #MAX_REVISIONS_PER_WORKLOAD} is exceeded -- the same
   * deterministic-under-replay oldest-first pruning {@link #putInstanceEvent} already establishes.
   * Pruning by append order is always safe here: a rollback never rewrites history in place, it
   * always appends a brand-new latest revision (see {@code ApiServer}'s own rollback handlers), so
   * the currently-live revision is by construction never the oldest one being pruned.
   */
  public void putControllerRevision(ControllerRevision revision) {
    String key =
        ControllerRevision.revisionKey(
            revision.workloadKind(), revision.spec().tenantId(), revision.name());
    controllerRevisions.compute(
        key,
        (k, existing) -> {
          List<ControllerRevision> updated =
              new ArrayList<>(existing == null ? List.of() : existing);
          updated.add(revision);
          while (updated.size() > MAX_REVISIONS_PER_WORKLOAD) {
            ControllerRevision oldest = updated.remove(0);
          }
          return List.copyOf(updated);
        });
  }

  /** Newest-first, matching {@link #listInstanceEvents}'s own timeline read order. */
  public List<ControllerRevision> listControllerRevisions(
      String workloadKind, Optional<String> tenantId, String name) {
    List<ControllerRevision> revisions =
        controllerRevisions.getOrDefault(
            ControllerRevision.revisionKey(workloadKind, tenantId, name), List.of());
    List<ControllerRevision> reversed = new ArrayList<>(revisions);
    Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  public Optional<ControllerRevision> getControllerRevision(
      String workloadKind, Optional<String> tenantId, String name, int revision) {
    return controllerRevisions
        .getOrDefault(ControllerRevision.revisionKey(workloadKind, tenantId, name), List.of())
        .stream()
        .filter(r -> r.revision() == revision)
        .findFirst();
  }

  // ---- limit range ----

  public void putLimitRange(LimitRangeSpec spec) {
    limitRanges.put(spec.tenantId(), spec);
  }

  public Optional<LimitRangeSpec> getLimitRange(String tenantId) {
    return Optional.ofNullable(limitRanges.get(tenantId));
  }

  public List<LimitRangeSpec> listLimitRanges() {
    return List.copyOf(limitRanges.values());
  }

  public void removeLimitRange(String tenantId) {
    limitRanges.remove(tenantId);
  }

  // ---- limit-range-violation bookkeeping ----

  /**
   * Set by {@code LimitRangeReconciler} every tick, read by the API server's deployment status
   * surface -- same level-triggered, remove-the-file-when-false shape as {@link
   * #putQuotaViolation}, but a separate flag: "violates this tenant's LimitRange" and "over the
   * tenant's aggregate quota" are independently-true-or-false failure modes. Unlike the quota flag,
   * this one also carries {@code reason} -- {@link LimitRangeSpec#violation}'s own description of
   * which bound is failing -- so an operator reading deployment status doesn't have to re-derive
   * why from the tenant's current range and the deployment's own manifest. A blank {@code reason}
   * clears the violation, matching {@code putQuotaViolation(name, false)}'s own convention.
   */
  public void putLimitRangeViolation(
      Optional<String> tenantId, String deploymentName, String reason) {
    String key = scopedKey(tenantId, deploymentName);
    if (reason == null || reason.isBlank()) {
      limitRangeViolations.remove(key);
      return;
    }
    limitRangeViolations.put(key, reason);
  }

  public boolean isLimitRangeViolating(Optional<String> tenantId, String deploymentName) {
    return limitRangeViolations.containsKey(scopedKey(tenantId, deploymentName));
  }

  public Optional<String> limitRangeViolationReason(
      Optional<String> tenantId, String deploymentName) {
    return Optional.ofNullable(limitRangeViolations.get(scopedKey(tenantId, deploymentName)));
  }

  // ---- custom-kind definitions ----

  /**
   * Stores {@code spec} with a store-assigned generation of current + 1, ignoring whatever
   * generation the proposer's copy carried -- the same lineage discipline {@link #putDeployment}'s
   * own generation merge follows, so every replica assigns the identical value under Raft's
   * strict-order replay.
   */
  public void putKindDefinition(KindDefinitionSpec spec) {
    long next = getKindDefinitionGeneration(spec.kindName()) + 1;
    kindDefinitions.put(spec.kindName(), spec.withGeneration(next));
  }

  public Optional<KindDefinitionSpec> getKindDefinition(String kindName) {
    return Optional.ofNullable(kindDefinitions.get(kindName));
  }

  /** 0 for a kind never defined or since removed -- the compare-and-set precondition value. */
  public long getKindDefinitionGeneration(String kindName) {
    KindDefinitionSpec existing = kindDefinitions.get(kindName);
    return existing == null ? 0L : existing.generation();
  }

  public List<KindDefinitionSpec> listKindDefinitions() {
    return List.copyOf(kindDefinitions.values());
  }

  /**
   * Removes only the definition itself -- {@code StateMutation.RemoveKindDefinition} refuses to
   * ever reach here while instances of the kind exist, so a stored instance can never be orphaned
   * with no schema to validate or display it.
   */
  public void removeKindDefinition(String kindName) {
    kindDefinitions.remove(kindName);
  }

  // ---- custom resources ----

  /**
   * Stores {@code resource}'s spec with a store-assigned generation of current + 1, preserving any
   * status an operator already reported -- a spec update must never stomp the operator's own
   * last-reported status, which travels only through {@link #putCustomResourceStatus}. A brand-new
   * resource starts with the proposer's own (typically empty) status bytes.
   */
  public void putCustomResource(CustomResource resource) {
    String key = customResourceKey(resource.kindName(), resource.tenantId(), resource.name());
    CustomResource existing = customResources.get(key);
    long next = (existing == null ? 0L : existing.generation()) + 1;
    byte[] status = existing == null ? resource.statusJson() : existing.statusJson();
    customResources.put(
        key,
        new CustomResource(
            resource.kindName(),
            resource.name(),
            resource.tenantId(),
            resource.specJson(),
            status,
            next));
  }

  public Optional<CustomResource> getCustomResource(
      String kindName, Optional<String> tenantId, String name) {
    return Optional.ofNullable(customResources.get(customResourceKey(kindName, tenantId, name)));
  }

  /** 0 for an instance that has never existed or was removed -- the CAS precondition value. */
  public long getCustomResourceGeneration(String kindName, Optional<String> tenantId, String name) {
    CustomResource existing = customResources.get(customResourceKey(kindName, tenantId, name));
    return existing == null ? 0L : existing.generation();
  }

  public List<CustomResource> listCustomResources(String kindName) {
    return customResources.values().stream().filter(r -> r.kindName().equals(kindName)).toList();
  }

  public List<CustomResource> listCustomResourcesFor(String kindName, Optional<String> tenantId) {
    return customResources.values().stream()
        .filter(r -> r.kindName().equals(kindName) && r.tenantId().equals(tenantId))
        .toList();
  }

  public void removeCustomResource(String kindName, Optional<String> tenantId, String name) {
    customResources.remove(customResourceKey(kindName, tenantId, name));
  }

  /**
   * Replaces only the status sub-document, never bumping the generation -- status is last-write-
   * wins, and an operator's {@code observedGeneration} claim lives inside the JSON it reports, not
   * in the store's own lineage counter. A status for an instance that no longer exists is silently
   * dropped: the instance was deleted out from under a level-triggered operator mid-tick, and its
   * next pass will observe the absence and stop reporting.
   */
  public void putCustomResourceStatus(
      String kindName, Optional<String> tenantId, String name, byte[] statusJson) {
    customResources.computeIfPresent(
        customResourceKey(kindName, tenantId, name),
        (key, existing) ->
            new CustomResource(
                existing.kindName(),
                existing.name(),
                existing.tenantId(),
                existing.specJson(),
                statusJson,
                existing.generation()));
  }

  // ---- full-state snapshot ----

  /**
   * A point-in-time copy of every resource kind Raft replicates -- deliberately excludes {@code
   * nodeHeartbeats}: heartbeats never enter the replicated log, so they have no business surviving
   * into a snapshot a follower installs either.
   */
  public StateSnapshot snapshot() {
    return new StateSnapshot(
        List.copyOf(deployments.values()),
        Map.copyOf(deploymentGenerations),
        List.copyOf(assignments.values()),
        List.copyOf(jobSpecs.values()),
        List.copyOf(jobRuns.values()),
        Map.copyOf(jobPhases),
        List.copyOf(jobRunSummaries.values()),
        List.copyOf(cronJobSpecs.values()),
        Map.copyOf(cronJobLastSchedule),
        List.copyOf(daemonSetSpecs.values()),
        List.copyOf(daemonSetAssignments.values()),
        rollingDaemonSetNodesSnapshot(),
        List.copyOf(statefulSetSpecs.values()),
        List.copyOf(statefulSetAssignments.values()),
        Map.copyOf(rollingStatefulSetIndices),
        Map.copyOf(statefulSetIndexNodes),
        List.copyOf(nodeRegistrations.values()),
        rollingIndicesSnapshot(),
        surgeIndices.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue()))),
        Map.copyOf(effectiveReplicas),
        List.copyOf(tenants.values()),
        quotaViolations.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet()),
        List.copyOf(configEntries.values()),
        List.copyOf(roles.values()),
        List.copyOf(roleBindings.values()),
        List.copyOf(accounts.values()),
        List.copyOf(reconcilerInstanceStates.values()),
        nodeCordons.entrySet().stream()
            .filter(Map.Entry::getValue)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet()),
        Map.copyOf(instanceEvents),
        auditEventsSnapshotOrder(),
        List.copyOf(services.values()),
        List.copyOf(networkPolicies.values()),
        controllerRevisions.values().stream().flatMap(List::stream).toList(),
        List.copyOf(limitRanges.values()),
        Map.copyOf(limitRangeViolations),
        Set.copyOf(revokedCertificateSerials.keySet()),
        List.copyOf(workloadTokens.values()),
        nodeTaintsSnapshot(),
        List.copyOf(kindDefinitions.values()),
        List.copyOf(customResources.values()),
        List.copyOf(workloadHealthStates.values()),
        Map.copyOf(sessionRevokedBeforeEpochMilli),
        List.copyOf(alertRules.values()),
        Map.copyOf(deploymentLastScale),
        List.copyOf(ingresses.values()),
        Map.copyOf(daemonSetDesiredCounts),
        Map.copyOf(alertFiringState),
        Set.copyOf(retiredSecretsKeyIds.keySet()));
  }

  /** The deep-copied {@code nodeTaints} shape {@link #snapshot()} embeds. */
  private Map<String, Set<String>> nodeTaintsSnapshot() {
    return nodeTaints.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
  }

  /** Oldest-first, matching {@link #auditEvents}' own internal order -- see {@link #snapshot()}. */
  private List<AuditEvent> auditEventsSnapshotOrder() {
    synchronized (auditEventsLock) {
      return List.copyOf(auditEvents);
    }
  }

  /** The deep-copied {@code rollingDaemonSetNodes} shape {@link #snapshot()} embeds. */
  private Map<String, Set<String>> rollingDaemonSetNodesSnapshot() {
    return rollingDaemonSetNodes.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
  }

  /** The deep-copied {@code rollingIndices} shape {@link #snapshot()} embeds. */
  private Map<String, Set<Integer>> rollingIndicesSnapshot() {
    return rollingIndices.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
  }

  /**
   * Replaces every resource this store holds with {@code snapshot}'s contents -- a follower's
   * response to a leader's {@code InstallSnapshot}, used when this replica has fallen too far
   * behind to catch up via ordinary log replay.
   */
  public void restoreFromSnapshot(StateSnapshot snapshot) {
    // A full wipe of every map this store holds, rather than iterating each resource kind's own
    // keys and calling its public per-key remove -- the tenant-scoped compound keys most of these
    // maps now use can't be un-parsed back into (tenantId, name) the way the old flat-string keys
    // briefly could, and there is no need to: every one of these maps is about to be repopulated
    // wholesale from the snapshot's own lists below, so an individual remove's side effects (e.g.
    // removeDeployment's own ControllerRevision cleanup) would be immediately redundant with the
    // clears already happening here.
    deployments.clear();
    deploymentGenerations.clear();
    services.clear();
    networkPolicies.clear();
    limitRanges.clear();
    limitRangeViolations.clear();
    assignments.clear();
    jobRuns.clear();
    jobSpecs.clear();
    jobPhases.clear();
    jobRunSummaries.clear();
    cronJobSpecs.clear();
    cronJobLastSchedule.clear();
    daemonSetAssignments.clear();
    daemonSetSpecs.clear();
    rollingDaemonSetNodes.clear();
    daemonSetDesiredCounts.clear();
    statefulSetAssignments.clear();
    statefulSetSpecs.clear();
    statefulSetIndexNodes.clear();
    rollingStatefulSetIndices.clear();
    nodeRegistrations.clear();
    tenants.clear();
    quotaViolations.clear();
    nodeCordons.clear();
    nodeTaints.clear();
    revokedCertificateSerials.clear();
    retiredSecretsKeyIds.clear();
    workloadTokens.clear();
    sessionRevokedBeforeEpochMilli.clear();
    configEntries.clear();
    roles.clear();
    roleBindings.clear();
    accounts.clear();
    reconcilerInstanceStates.clear();
    workloadHealthStates.clear();
    rollingIndices.clear();
    surgeIndices.clear();
    effectiveReplicas.clear();
    deploymentLastScale.clear();
    kindDefinitions.clear();
    customResources.clear();
    alertFiringState.clear();
    clearAllInstanceEvents();
    clearAllAuditEvents();
    clearAllControllerRevisions();

    snapshot.deployments().forEach(this::putDeployment);
    // putDeployment above increments each name's generation from this replay's own arbitrary
    // starting point (0), not the true accumulated value every other replica that replayed the
    // full log instead of a snapshot already holds -- stomp with the snapshot's own recorded
    // values so a restored replica's CAS decisions agree with the rest of the cluster.
    deploymentGenerations.clear();
    deploymentGenerations.putAll(snapshot.deploymentGenerations());
    snapshot.assignments().forEach(this::putAssignment);
    snapshot.jobSpecs().forEach(this::putJobSpec);
    snapshot.jobRuns().forEach(this::putJobRun);
    snapshot.jobPhases().forEach((key, phase) -> jobPhases.put(key, phase));
    snapshot.jobRunSummaries().forEach(this::putJobRunSummary);
    snapshot.cronJobSpecs().forEach(this::putCronJobSpec);
    snapshot
        .cronJobLastSchedule()
        .forEach((key, lastScheduleTime) -> cronJobLastSchedule.put(key, lastScheduleTime));
    snapshot.daemonSetSpecs().forEach(this::putDaemonSetSpec);
    snapshot.daemonSetAssignments().forEach(this::putDaemonSetAssignment);
    snapshot
        .rollingDaemonSetNodes()
        .forEach(
            (key, nodeIds) -> {
              Set<String> set = ConcurrentHashMap.newKeySet();
              set.addAll(nodeIds);
              rollingDaemonSetNodes.put(key, set);
            });
    snapshot.statefulSetSpecs().forEach(this::putStatefulSetSpec);
    snapshot.statefulSetAssignments().forEach(this::putStatefulSetAssignment);
    snapshot
        .rollingStatefulSetIndices()
        .forEach((key, index) -> rollingStatefulSetIndices.put(key, index));
    snapshot
        .statefulSetIndexNodes()
        .forEach((key, nodeId) -> statefulSetIndexNodes.put(key, nodeId));
    snapshot.nodeRegistrations().forEach(this::putNodeRegistration);
    snapshot
        .rollingIndices()
        .forEach(
            (key, indices) -> {
              Set<Integer> set = ConcurrentHashMap.newKeySet();
              set.addAll(indices);
              rollingIndices.put(key, set);
            });
    snapshot
        .surgeIndices()
        .forEach((key, indices) -> surgeIndices.put(key, new ConcurrentHashMap<>(indices)));
    snapshot.effectiveReplicas().forEach((key, replicas) -> effectiveReplicas.put(key, replicas));
    snapshot
        .deploymentLastScale()
        .forEach((key, lastScaleTime) -> deploymentLastScale.put(key, lastScaleTime));
    snapshot.tenants().forEach(this::putTenant);
    snapshot.quotaViolatingDeployments().forEach(key -> quotaViolations.put(key, Boolean.TRUE));
    snapshot.cordonedNodes().forEach(nodeId -> putNodeCordon(nodeId, true));
    snapshot
        .nodeTaints()
        .forEach(
            (nodeId, tenantIds) ->
                tenantIds.forEach(tenantId -> putNodeTaint(nodeId, tenantId, true)));
    snapshot.revokedCertificateSerials().forEach(serial -> putCertificateRevocation(serial, true));
    snapshot.retiredSecretsKeyIds().forEach(keyId -> putSecretsKeyRetirement(keyId, true));
    snapshot.workloadTokens().forEach(record -> putWorkloadToken(record, 0L));
    snapshot.sessionRevokedBeforeEpochMilli().forEach(this::putSessionRevocation);
    snapshot.configEntries().forEach(this::putConfigEntry);
    snapshot.roles().forEach(this::putRole);
    snapshot.roleBindings().forEach(this::putRoleBinding);
    snapshot.accounts().forEach(this::putAccount);
    snapshot.reconcilerInstanceStates().forEach(this::putReconcilerInstanceState);
    snapshot.workloadHealthStates().forEach(this::putWorkloadHealthState);
    // A direct bulk put rather than replaying through putInstanceEvent one event at a time --
    // StateSnapshot#instanceEvents is already keyed and ordered (oldest-first) exactly the way
    // this store's own instanceEvents field is, retention-cap-pruned by whichever replica took
    // the snapshot, so there is no pruning decision left to reproduce here.
    instanceEvents.putAll(snapshot.instanceEvents());
    // Same reasoning, cluster-wide: StateSnapshot#auditEvents is oldest-first (see
    // auditEventsSnapshotOrder() above), reproducing identical MAX_AUDIT_EVENTS pruning on replay.
    snapshot.auditEvents().forEach(this::putAuditEvent);
    snapshot.services().forEach(this::putService);
    snapshot.networkPolicies().forEach(this::putNetworkPolicy);
    snapshot.ingresses().forEach(this::putIngress);
    snapshot.alertRules().forEach(this::putAlertRule);
    snapshot.alertFiringState().forEach(alertFiringState::put);
    // Oldest-first, matching how putControllerRevision's own pruning expects to see them -- same
    // reasoning as the instanceEvents/auditEvents replay just above.
    snapshot.controllerRevisions().forEach(this::putControllerRevision);
    snapshot.limitRanges().forEach(this::putLimitRange);
    snapshot.limitRangeViolations().forEach((key, reason) -> limitRangeViolations.put(key, reason));
    // Direct raw puts rather than replaying through putKindDefinition/putCustomResource -- both of
    // those assign generation = current + 1, which from this replica's just-wiped starting point
    // would restart every lineage at 1 instead of the true accumulated value the rest of the
    // cluster holds, the same trap the deploymentGenerations stomp above closes.
    snapshot
        .kindDefinitions()
        .forEach(definition -> kindDefinitions.put(definition.kindName(), definition));
    snapshot
        .customResources()
        .forEach(
            resource ->
                customResources.put(
                    customResourceKey(resource.kindName(), resource.tenantId(), resource.name()),
                    resource));
    snapshot
        .daemonSetDesiredCounts()
        .forEach((key, count) -> daemonSetDesiredCounts.put(key, count));
  }

  /**
   * Full wipe used only by {@link #restoreFromSnapshot} -- unlike every other resource kind here,
   * instance events have no public per-key {@code remove}; pruning is deliberately internal to
   * {@link #putInstanceEvent} only, not a capability callers reach for directly.
   */
  private void clearAllInstanceEvents() {
    instanceEvents.clear();
  }

  /** Same rationale as {@link #clearAllInstanceEvents}, for the cluster-wide audit trail. */
  private void clearAllAuditEvents() {
    synchronized (auditEventsLock) {
      auditEvents.clear();
    }
  }

  /** Same rationale as {@link #clearAllInstanceEvents}, for revision history. */
  private void clearAllControllerRevisions() {
    controllerRevisions.clear();
  }

  /**
   * The store's canonical per-tenant identity for a named resource: an absent {@code tenantId} is
   * its own single untenanted namespace (unchanged from before per-tenant scoping existed -- two
   * untenanted resources still can't share a name), while a present one scopes {@code name} within
   * that tenant only, so a tenanted "api" and a different tenant's own "api" now occupy distinct
   * keys entirely rather than colliding in a single flat namespace. {@code '\0'} is the delimiter:
   * it can never appear in an operator-supplied name or tenant id (both are validated elsewhere to
   * be printable identifiers), so no real (tenantId, name) pair can ever collide across the split.
   */
  private static String scopedKey(Optional<String> tenantId, String name) {
    return tenantId.orElse("") + '\0' + name;
  }

  /**
   * {@link #scopedKey}'s counterpart for a resource kind ({@link NetworkPolicySpec}) whose own
   * tenant scoping is mandatory rather than optional.
   */
  private static String scopedKey(String tenantId, String name) {
    return tenantId + '\0' + name;
  }

  private static String jobRunKey(Optional<String> tenantId, String jobName, int attempt) {
    return scopedKey(tenantId, jobName) + "#" + attempt;
  }

  private static String daemonSetAssignmentKey(
      Optional<String> tenantId, String daemonSetName, String nodeId) {
    return scopedKey(tenantId, daemonSetName) + "#" + nodeId;
  }

  private static String statefulSetAssignmentKey(
      Optional<String> tenantId, String statefulSetName, int instanceIndex) {
    return scopedKey(tenantId, statefulSetName) + "#" + instanceIndex;
  }

  private static String assignmentKey(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return scopedKey(tenantId, deploymentName) + "#" + instanceIndex;
  }

  private static String reconcilerStateKey(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return scopedKey(tenantId, deploymentName) + "#" + instanceIndex;
  }

  /**
   * {@code workloadKind} prefixes the key (rather than trailing it, as {@code slot} does) so a
   * Deployment- and StatefulSet-shaped key can never collide even when the tenant, name, and slot
   * all happen to coincide -- see {@link WorkloadHealthState}'s own javadoc.
   */
  private static String workloadHealthKey(
      Optional<String> tenantId, String workloadKind, String workloadName, String slot) {
    return workloadKind + "#" + scopedKey(tenantId, workloadName) + "#" + slot;
  }

  private static String instanceEventsKey(
      Optional<String> tenantId, String deploymentName, int instanceIndex) {
    return scopedKey(tenantId, deploymentName) + "#" + instanceIndex;
  }

  private static String configKey(String tenantId, String key) {
    return tenantId + "#" + key;
  }

  /**
   * {@link #scopedKey}'s pattern with the kind name prepended -- one flat map serves every custom
   * kind, so the key must carry the kind alongside the tenant/name pair. A kind name may contain
   * dots but never {@code '\0'}, the same reasoning that makes the scoped-key delimiter safe.
   */
  private static String customResourceKey(String kindName, Optional<String> tenantId, String name) {
    return kindName + '\0' + scopedKey(tenantId, name);
  }
}
