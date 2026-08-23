package com.gimle.mimir.store;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.CronJobManifestParser;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetManifestParser;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentManifestParser;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobManifestParser;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetManifestParser;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Embedded, single-node, file-backed state store: a directory of small YAML files, one per
 * resource, written via a temp file plus atomic move so a crash mid-write never leaves a torn file
 * where a reader could see it. An in-memory index is rebuilt from disk on construction; every
 * mutation writes through to disk before it's reflected in memory. Deliberately not an embedded SQL
 * engine or a hand-rolled binary format -- a Raft-replicated log sits on top of this layer
 * regardless of what's built here, so this is the least engineering that survives a control-plane
 * process restart, not a storage engine to build on.
 */
public final class StateStore implements StoreReader {

  private final Path root;
  private final Clock clock;
  private final Map<String, DeploymentSpec> deployments = new ConcurrentHashMap<>();
  private final Map<String, ServiceSpec> services = new ConcurrentHashMap<>();
  private final Map<String, NetworkPolicySpec> networkPolicies = new ConcurrentHashMap<>();
  private final Map<String, InstanceAssignment> assignments = new ConcurrentHashMap<>();
  private final Map<String, JobSpec> jobSpecs = new ConcurrentHashMap<>();
  private final Map<String, JobRun> jobRuns = new ConcurrentHashMap<>();
  // Only ever holds an entry once a job reaches a terminal phase -- absent means "still running"
  // (no attempt placed yet, or one currently in flight), the same "only persist the override"
  // posture effectiveReplicas/rollingIndices already use rather than writing an explicit RUNNING
  // marker for every job that hasn't finished yet.
  private final Map<String, JobPhase> jobPhases = new ConcurrentHashMap<>();
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
  private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
  private final Map<String, Boolean> quotaViolations = new ConcurrentHashMap<>();
  private final Map<String, Boolean> nodeCordons = new ConcurrentHashMap<>();
  private final Map<String, ConfigEntry> configEntries = new ConcurrentHashMap<>();
  private final Map<String, Role> roles = new ConcurrentHashMap<>();
  private final Map<String, RoleBinding> roleBindings = new ConcurrentHashMap<>();
  private final Map<String, Account> accounts = new ConcurrentHashMap<>();
  private final Map<String, ReconcilerInstanceState> reconcilerInstanceStates =
      new ConcurrentHashMap<>();

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

  public StateStore(Path root) {
    this(root, Clock.systemUTC());
  }

  /**
   * Injectable-clock variant, for tests that need to reason about how <em>old</em> a heartbeat or a
   * lease is. Those two are the only timestamps this store stamps itself; everything else it holds
   * was timestamped by whoever proposed the mutation. Without this seam a test can advance a
   * reconciler's clock but not the store's, so "this node has been silent for 20 seconds" is not
   * expressible at all -- the heartbeat always looks like it arrived just now.
   */
  public StateStore(Path root, Clock clock) {
    this.root = root;
    this.clock = clock;
    try {
      Files.createDirectories(deploymentsDir());
      Files.createDirectories(servicesDir());
      Files.createDirectories(networkPoliciesDir());
      Files.createDirectories(assignmentsDir());
      Files.createDirectories(jobsDir());
      Files.createDirectories(jobRunsDir());
      Files.createDirectories(jobPhasesDir());
      Files.createDirectories(cronJobsDir());
      Files.createDirectories(cronJobLastScheduleDir());
      Files.createDirectories(daemonSetsDir());
      Files.createDirectories(daemonSetAssignmentsDir());
      Files.createDirectories(rollingDaemonSetDir());
      Files.createDirectories(statefulSetsDir());
      Files.createDirectories(statefulSetAssignmentsDir());
      Files.createDirectories(rollingStatefulSetDir());
      Files.createDirectories(statefulSetIndexNodesDir());
      Files.createDirectories(nodesDir());
      Files.createDirectories(rollingDir());
      Files.createDirectories(surgeDir());
      Files.createDirectories(autoscaleDir());
      Files.createDirectories(tenantsDir());
      Files.createDirectories(quotaDir());
      Files.createDirectories(cordonDir());
      Files.createDirectories(configDir());
      Files.createDirectories(rolesDir());
      Files.createDirectories(roleBindingsDir());
      Files.createDirectories(accountsDir());
      Files.createDirectories(reconcilerStateDir());
      Files.createDirectories(instanceEventsDir());
      Files.createDirectories(auditEventsDir());
      Files.createDirectories(controllerRevisionsDir());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    loadAll();
  }

  // ---- deployments ----

  public void putDeployment(DeploymentSpec spec) {
    writeAtomically(deploymentFile(spec.name()), deploymentToYaml(spec));
    deployments.put(spec.name(), spec);
  }

  public Optional<DeploymentSpec> getDeployment(String name) {
    return Optional.ofNullable(deployments.get(name));
  }

  public List<DeploymentSpec> listDeployments() {
    return List.copyOf(deployments.values());
  }

  public void removeDeployment(String name) {
    deleteQuietly(deploymentFile(name));
    deployments.remove(name);
    clearAllRollingIndices(name);
    clearAllSurgeIndices(name);
    deleteQuietly(effectiveReplicasFile(name));
    effectiveReplicas.remove(name);
  }

  // ---- services ----

  public void putService(ServiceSpec spec) {
    writeAtomically(serviceFile(spec.name()), serviceSpecToYaml(spec));
    services.put(spec.name(), spec);
  }

  public Optional<ServiceSpec> getService(String name) {
    return Optional.ofNullable(services.get(name));
  }

  public List<ServiceSpec> listServices() {
    return List.copyOf(services.values());
  }

  public void removeService(String name) {
    deleteQuietly(serviceFile(name));
    services.remove(name);
  }

  // ---- network policies ----

  public void putNetworkPolicy(NetworkPolicySpec spec) {
    writeAtomically(networkPolicyFile(spec.name()), networkPolicySpecToYaml(spec));
    networkPolicies.put(spec.name(), spec);
  }

  public Optional<NetworkPolicySpec> getNetworkPolicy(String name) {
    return Optional.ofNullable(networkPolicies.get(name));
  }

  public List<NetworkPolicySpec> listNetworkPolicies() {
    return List.copyOf(networkPolicies.values());
  }

  public void removeNetworkPolicy(String name) {
    deleteQuietly(networkPolicyFile(name));
    networkPolicies.remove(name);
  }

  // ---- assignments ----

  public void putAssignment(InstanceAssignment assignment) {
    writeAtomically(
        assignmentFile(assignment.deploymentName(), assignment.instanceIndex()),
        assignmentToYaml(assignment));
    assignments.put(
        assignmentKey(assignment.deploymentName(), assignment.instanceIndex()), assignment);
  }

  public void removeAssignment(String deploymentName, int instanceIndex) {
    deleteQuietly(assignmentFile(deploymentName, instanceIndex));
    assignments.remove(assignmentKey(deploymentName, instanceIndex));
  }

  public List<InstanceAssignment> listAssignments() {
    return List.copyOf(assignments.values());
  }

  public List<InstanceAssignment> listAssignmentsFor(String deploymentName) {
    return assignments.values().stream()
        .filter(a -> a.deploymentName().equals(deploymentName))
        .toList();
  }

  // ---- jobs ----

  public void putJobSpec(JobSpec spec) {
    writeAtomically(jobFile(spec.name()), jobSpecToYaml(spec));
    jobSpecs.put(spec.name(), spec);
  }

  public Optional<JobSpec> getJobSpec(String name) {
    return Optional.ofNullable(jobSpecs.get(name));
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
  public void removeJobSpec(String name) {
    deleteQuietly(jobFile(name));
    jobSpecs.remove(name);
    deleteQuietly(jobPhaseFile(name));
    jobPhases.remove(name);
  }

  // ---- job runs ----

  public void putJobRun(JobRun run) {
    writeAtomically(jobRunFile(run.jobName(), run.attempt()), jobRunToYaml(run));
    jobRuns.put(jobRunKey(run.jobName(), run.attempt()), run);
  }

  public void removeJobRun(String jobName, int attempt) {
    deleteQuietly(jobRunFile(jobName, attempt));
    jobRuns.remove(jobRunKey(jobName, attempt));
  }

  public List<JobRun> listJobRuns() {
    return List.copyOf(jobRuns.values());
  }

  public List<JobRun> listJobRunsFor(String jobName) {
    return jobRuns.values().stream().filter(r -> r.jobName().equals(jobName)).toList();
  }

  // ---- job phase ----

  public void putJobPhase(String jobName, JobPhase phase) {
    writeAtomically(jobPhaseFile(jobName), jobPhaseToYaml(phase));
    jobPhases.put(jobName, phase);
  }

  /** Empty means "not yet terminal" -- see {@link #jobPhases}'s own field javadoc. */
  public Optional<JobPhase> getJobPhase(String jobName) {
    return Optional.ofNullable(jobPhases.get(jobName));
  }

  // ---- cronjobs ----

  public void putCronJobSpec(CronJobSpec spec) {
    writeAtomically(cronJobFile(spec.name()), cronJobSpecToYaml(spec));
    cronJobSpecs.put(spec.name(), spec);
  }

  public Optional<CronJobSpec> getCronJobSpec(String name) {
    return Optional.ofNullable(cronJobSpecs.get(name));
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
  public void removeCronJobSpec(String name) {
    deleteQuietly(cronJobFile(name));
    cronJobSpecs.remove(name);
    deleteQuietly(cronJobLastScheduleFile(name));
    cronJobLastSchedule.remove(name);
  }

  // ---- cronjob last-schedule bookkeeping ----

  public void putCronJobLastSchedule(String name, Instant lastScheduleTime) {
    writeAtomically(cronJobLastScheduleFile(name), cronJobLastScheduleToYaml(lastScheduleTime));
    cronJobLastSchedule.put(name, lastScheduleTime);
  }

  /** Empty means "never fired yet" -- see {@link #cronJobLastSchedule}'s own field javadoc. */
  public Optional<Instant> getCronJobLastSchedule(String name) {
    return Optional.ofNullable(cronJobLastSchedule.get(name));
  }

  // ---- daemonsets ----

  public void putDaemonSetSpec(DaemonSetSpec spec) {
    writeAtomically(daemonSetFile(spec.name()), daemonSetSpecToYaml(spec));
    daemonSetSpecs.put(spec.name(), spec);
  }

  public Optional<DaemonSetSpec> getDaemonSetSpec(String name) {
    return Optional.ofNullable(daemonSetSpecs.get(name));
  }

  public List<DaemonSetSpec> listDaemonSetSpecs() {
    return List.copyOf(daemonSetSpecs.values());
  }

  /**
   * Also clears any in-flight {@link #rollingDaemonSetNodes} entry for {@code name} -- a removed
   * DaemonSet has no rollout left to track. Does not remove any {@link DaemonSetAssignment} still
   * on disk for it; {@code DaemonSetReconciler}'s own orphaned-assignment sweep is responsible for
   * those, the same "spec gone -> reconciler tears assignments down" ordering {@link
   * #removeDeployment} already relies on for {@link InstanceAssignment}.
   */
  public void removeDaemonSetSpec(String name) {
    deleteQuietly(daemonSetFile(name));
    daemonSetSpecs.remove(name);
    clearAllRollingDaemonSetNodes(name);
  }

  // ---- daemonset assignments ----

  public void putDaemonSetAssignment(DaemonSetAssignment assignment) {
    writeAtomically(
        daemonSetAssignmentFile(assignment.daemonSetName(), assignment.nodeId()),
        daemonSetAssignmentToYaml(assignment));
    daemonSetAssignments.put(
        daemonSetAssignmentKey(assignment.daemonSetName(), assignment.nodeId()), assignment);
  }

  public void removeDaemonSetAssignment(String daemonSetName, String nodeId) {
    deleteQuietly(daemonSetAssignmentFile(daemonSetName, nodeId));
    daemonSetAssignments.remove(daemonSetAssignmentKey(daemonSetName, nodeId));
  }

  public List<DaemonSetAssignment> listDaemonSetAssignments() {
    return List.copyOf(daemonSetAssignments.values());
  }

  public List<DaemonSetAssignment> listDaemonSetAssignmentsFor(String daemonSetName) {
    return daemonSetAssignments.values().stream()
        .filter(a -> a.daemonSetName().equals(daemonSetName))
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
  public void addRollingDaemonSetNode(String daemonSetName, String nodeId) {
    writeAtomically(
        rollingDaemonSetFile(daemonSetName, nodeId),
        rollingDaemonSetNodeToYaml(daemonSetName, nodeId));
    rollingDaemonSetNodes
        .computeIfAbsent(daemonSetName, key -> ConcurrentHashMap.newKeySet())
        .add(nodeId);
  }

  public void removeRollingDaemonSetNode(String daemonSetName, String nodeId) {
    deleteQuietly(rollingDaemonSetFile(daemonSetName, nodeId));
    Set<String> nodes = rollingDaemonSetNodes.get(daemonSetName);
    if (nodes != null) {
      nodes.remove(nodeId);
    }
  }

  /** Every node currently in flight for this DaemonSet's rollout; empty means none. */
  public Set<String> getRollingDaemonSetNodes(String daemonSetName) {
    return Set.copyOf(rollingDaemonSetNodes.getOrDefault(daemonSetName, Set.of()));
  }

  private void clearAllRollingDaemonSetNodes(String daemonSetName) {
    Set.copyOf(rollingDaemonSetNodes.getOrDefault(daemonSetName, Set.of()))
        .forEach(nodeId -> removeRollingDaemonSetNode(daemonSetName, nodeId));
  }

  // ---- statefulsets ----

  public void putStatefulSetSpec(StatefulSetSpec spec) {
    writeAtomically(statefulSetFile(spec.name()), statefulSetSpecToYaml(spec));
    statefulSetSpecs.put(spec.name(), spec);
  }

  public Optional<StatefulSetSpec> getStatefulSetSpec(String name) {
    return Optional.ofNullable(statefulSetSpecs.get(name));
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
   * reconciler tears assignments down" ordering {@link #removeDeployment} already relies on).
   */
  public void removeStatefulSetSpec(String name) {
    deleteQuietly(statefulSetFile(name));
    statefulSetSpecs.remove(name);
    clearRollingStatefulSetIndex(name);
  }

  // ---- statefulset assignments ----

  public void putStatefulSetAssignment(StatefulSetAssignment assignment) {
    writeAtomically(
        statefulSetAssignmentFile(assignment.statefulSetName(), assignment.instanceIndex()),
        statefulSetAssignmentToYaml(assignment));
    statefulSetAssignments.put(
        statefulSetAssignmentKey(assignment.statefulSetName(), assignment.instanceIndex()),
        assignment);
  }

  public void removeStatefulSetAssignment(String statefulSetName, int instanceIndex) {
    deleteQuietly(statefulSetAssignmentFile(statefulSetName, instanceIndex));
    statefulSetAssignments.remove(statefulSetAssignmentKey(statefulSetName, instanceIndex));
  }

  public List<StatefulSetAssignment> listStatefulSetAssignments() {
    return List.copyOf(statefulSetAssignments.values());
  }

  public List<StatefulSetAssignment> listStatefulSetAssignmentsFor(String statefulSetName) {
    return statefulSetAssignments.values().stream()
        .filter(a -> a.statefulSetName().equals(statefulSetName))
        .toList();
  }

  // ---- statefulset rolling-update / OrderedReady bookkeeping ----

  /**
   * The single index currently in flight for a StatefulSet, if any -- see {@link
   * StateMutation.PutRollingStatefulSetIndex}'s own javadoc for why this one marker governs both
   * ordinary {@code OrderedReady} scale-up admission and rolling-update admission.
   */
  public void putRollingStatefulSetIndex(String statefulSetName, int instanceIndex) {
    writeAtomically(rollingStatefulSetFile(statefulSetName), rollingToYaml(instanceIndex));
    rollingStatefulSetIndices.put(statefulSetName, instanceIndex);
  }

  public void clearRollingStatefulSetIndex(String statefulSetName) {
    deleteQuietly(rollingStatefulSetFile(statefulSetName));
    rollingStatefulSetIndices.remove(statefulSetName);
  }

  public Optional<Integer> getRollingStatefulSetIndex(String statefulSetName) {
    return Optional.ofNullable(rollingStatefulSetIndices.get(statefulSetName));
  }

  // ---- statefulset sticky node-binding bookkeeping ----

  /**
   * See {@link StateMutation.PutStatefulSetIndexNode}'s own javadoc: written once, the first time
   * an index is ever placed, read back by every later placement attempt for that same index as
   * {@code Scheduler}'s {@code stickyNodeId} input, and left untouched by an ordinary assignment
   * removal.
   */
  public void putStatefulSetIndexNode(String statefulSetName, int instanceIndex, String nodeId) {
    writeAtomically(
        statefulSetIndexNodeFile(statefulSetName, instanceIndex),
        statefulSetIndexNodeToYaml(statefulSetName, instanceIndex, nodeId));
    statefulSetIndexNodes.put(statefulSetAssignmentKey(statefulSetName, instanceIndex), nodeId);
  }

  /** Fired only on genuinely permanent index removal -- see the field's own javadoc. */
  public void removeStatefulSetIndexNode(String statefulSetName, int instanceIndex) {
    deleteQuietly(statefulSetIndexNodeFile(statefulSetName, instanceIndex));
    statefulSetIndexNodes.remove(statefulSetAssignmentKey(statefulSetName, instanceIndex));
  }

  public Optional<String> getStatefulSetIndexNode(String statefulSetName, int instanceIndex) {
    return Optional.ofNullable(
        statefulSetIndexNodes.get(statefulSetAssignmentKey(statefulSetName, instanceIndex)));
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
  public void addRollingIndex(String deploymentName, int instanceIndex) {
    writeAtomically(
        rollingFile(deploymentName, instanceIndex),
        rollingIndexToYaml(deploymentName, instanceIndex));
    rollingIndices
        .computeIfAbsent(deploymentName, key -> ConcurrentHashMap.newKeySet())
        .add(instanceIndex);
  }

  public void removeRollingIndex(String deploymentName, int instanceIndex) {
    deleteQuietly(rollingFile(deploymentName, instanceIndex));
    Set<Integer> indices = rollingIndices.get(deploymentName);
    if (indices != null) {
      indices.remove(instanceIndex);
    }
  }

  /** Every instance index currently in flight for this deployment's rollout; empty means none. */
  public Set<Integer> getRollingIndices(String deploymentName) {
    return Set.copyOf(rollingIndices.getOrDefault(deploymentName, Set.of()));
  }

  private void clearAllRollingIndices(String deploymentName) {
    Set.copyOf(rollingIndices.getOrDefault(deploymentName, Set.of()))
        .forEach(index -> removeRollingIndex(deploymentName, index));
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
  public void addSurgeIndex(String deploymentName, int surgeIndex, int targetIndex) {
    writeAtomically(
        surgeFile(deploymentName, surgeIndex),
        surgeIndexToYaml(deploymentName, surgeIndex, targetIndex));
    surgeIndices
        .computeIfAbsent(deploymentName, key -> new ConcurrentHashMap<>())
        .put(surgeIndex, targetIndex);
  }

  public void removeSurgeIndex(String deploymentName, int surgeIndex) {
    deleteQuietly(surgeFile(deploymentName, surgeIndex));
    Map<Integer, Integer> indices = surgeIndices.get(deploymentName);
    if (indices != null) {
      indices.remove(surgeIndex);
    }
  }

  /**
   * Every surge slot currently in flight for this deployment, keyed by surgeIndex; empty means
   * none.
   */
  public Map<Integer, Integer> getSurgeIndices(String deploymentName) {
    return Map.copyOf(surgeIndices.getOrDefault(deploymentName, Map.of()));
  }

  private void clearAllSurgeIndices(String deploymentName) {
    Set.copyOf(surgeIndices.getOrDefault(deploymentName, Map.of()).keySet())
        .forEach(surgeIndex -> removeSurgeIndex(deploymentName, surgeIndex));
  }

  // ---- autoscaling bookkeeping ----

  /**
   * The autoscaler's current target replica count, read by {@link
   * com.gimle.controlplane.reconcile.DeploymentReconciler} in place of {@code
   * DeploymentSpec#replicas()} whenever a deployment carries an {@code autoscale} policy.
   */
  public void putEffectiveReplicas(String deploymentName, int replicas) {
    writeAtomically(effectiveReplicasFile(deploymentName), effectiveReplicasToYaml(replicas));
    effectiveReplicas.put(deploymentName, replicas);
  }

  public Optional<Integer> getEffectiveReplicas(String deploymentName) {
    return Optional.ofNullable(effectiveReplicas.get(deploymentName));
  }

  // ---- node registrations ----

  public void putNodeRegistration(NodeRegistration registration) {
    writeAtomically(registrationFile(registration.nodeId()), registrationToYaml(registration));
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
    deleteQuietly(registrationFile(nodeId));
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
    writeAtomically(heartbeatFile(heartbeat.nodeId()), heartbeatToYaml(heartbeat, receivedAt));
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
    writeAtomically(tenantFile(tenant.id()), tenantToYaml(tenant));
    tenants.put(tenant.id(), tenant);
  }

  public Optional<Tenant> getTenant(String id) {
    return Optional.ofNullable(tenants.get(id));
  }

  public List<Tenant> listTenants() {
    return List.copyOf(tenants.values());
  }

  public void removeTenant(String id) {
    deleteQuietly(tenantFile(id));
    tenants.remove(id);
  }

  // ---- quota-violation bookkeeping ----

  /**
   * Set by {@code QuotaReconciler} every tick, read by the API server's deployment status surface
   * -- a level-triggered flag, not an event, so a deployment whose tenant's quota is retroactively
   * raised again clears automatically on the next tick without any special-cased "resolved" path.
   */
  public void putQuotaViolation(String deploymentName, boolean violating) {
    if (!violating) {
      deleteQuietly(quotaFile(deploymentName));
      quotaViolations.remove(deploymentName);
      return;
    }
    writeAtomically(quotaFile(deploymentName), "violating: true\n");
    quotaViolations.put(deploymentName, Boolean.TRUE);
  }

  public boolean isQuotaViolating(String deploymentName) {
    return quotaViolations.getOrDefault(deploymentName, Boolean.FALSE);
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
      deleteQuietly(cordonFile(nodeId));
      nodeCordons.remove(nodeId);
      return;
    }
    writeAtomically(cordonFile(nodeId), "cordoned: true\n");
    nodeCordons.put(nodeId, Boolean.TRUE);
  }

  public boolean isNodeCordoned(String nodeId) {
    return nodeCordons.getOrDefault(nodeId, Boolean.FALSE);
  }

  // ---- tenant-scoped config/secrets ----

  public void putConfigEntry(ConfigEntry entry) {
    String key = configKey(entry.tenantId(), entry.key());
    writeAtomically(configFile(entry.tenantId(), entry.key()), configEntryToYaml(entry));
    configEntries.put(key, entry);
  }

  public Optional<ConfigEntry> getConfigEntry(String tenantId, String key) {
    return Optional.ofNullable(configEntries.get(configKey(tenantId, key)));
  }

  public List<ConfigEntry> listConfigEntriesFor(String tenantId) {
    return configEntries.values().stream().filter(e -> e.tenantId().equals(tenantId)).toList();
  }

  public void removeConfigEntry(String tenantId, String key) {
    deleteQuietly(configFile(tenantId, key));
    configEntries.remove(configKey(tenantId, key));
  }

  // ---- roles ----

  public void putRole(Role role) {
    writeAtomically(roleFile(role.name()), roleToYaml(role));
    roles.put(role.name(), role);
  }

  public Optional<Role> getRole(String name) {
    return Optional.ofNullable(roles.get(name));
  }

  public List<Role> listRoles() {
    return List.copyOf(roles.values());
  }

  public void removeRole(String name) {
    deleteQuietly(roleFile(name));
    roles.remove(name);
  }

  // ---- role bindings ----

  public void putRoleBinding(RoleBinding binding) {
    writeAtomically(roleBindingFile(binding.id()), roleBindingToYaml(binding));
    roleBindings.put(binding.id(), binding);
  }

  public Optional<RoleBinding> getRoleBinding(String id) {
    return Optional.ofNullable(roleBindings.get(id));
  }

  public List<RoleBinding> listRoleBindings() {
    return List.copyOf(roleBindings.values());
  }

  public void removeRoleBinding(String id) {
    deleteQuietly(roleBindingFile(id));
    roleBindings.remove(id);
  }

  // ---- accounts ----

  /**
   * Console-login-only, see {@link Account}'s own javadoc. {@link #listAccounts()} being empty is
   * exactly the signal {@code ApiServer} checks before seeding a bootstrap account from {@code
   * gimle-pki}'s {@code bootstrap-account.yaml} -- never re-seeded once any account exists.
   */
  public void putAccount(Account account) {
    writeAtomically(accountFile(account.username()), accountToYaml(account));
    accounts.put(account.username(), account);
  }

  public Optional<Account> getAccount(String username) {
    return Optional.ofNullable(accounts.get(username));
  }

  public List<Account> listAccounts() {
    return List.copyOf(accounts.values());
  }

  public void removeAccount(String username) {
    deleteQuietly(accountFile(username));
    accounts.remove(username);
  }

  // ---- reconciler backoff/grace-period bookkeeping ----

  /**
   * Written by {@code HealthReconciler} and {@code ReplicaCountReconciler} alike -- see {@link
   * ReconcilerInstanceState}'s own javadoc for why both share one resource kind.
   */
  public void putReconcilerInstanceState(ReconcilerInstanceState state) {
    writeAtomically(
        reconcilerStateFile(state.deploymentName(), state.instanceIndex()),
        reconcilerInstanceStateToYaml(state));
    reconcilerInstanceStates.put(
        reconcilerStateKey(state.deploymentName(), state.instanceIndex()), state);
  }

  public Optional<ReconcilerInstanceState> getReconcilerInstanceState(
      String deploymentName, int instanceIndex) {
    return Optional.ofNullable(
        reconcilerInstanceStates.get(reconcilerStateKey(deploymentName, instanceIndex)));
  }

  public void removeReconcilerInstanceState(String deploymentName, int instanceIndex) {
    deleteQuietly(reconcilerStateFile(deploymentName, instanceIndex));
    reconcilerInstanceStates.remove(reconcilerStateKey(deploymentName, instanceIndex));
  }

  public List<ReconcilerInstanceState> listReconcilerInstanceStates() {
    return List.copyOf(reconcilerInstanceStates.values());
  }

  // ---- per-instance lifecycle event log ----

  /**
   * Appends one event to {@code event.deploymentName()}/{@code event.instanceIndex()}'s timeline,
   * pruning the oldest event(s) once {@link #MAX_EVENTS_PER_INSTANCE} is exceeded -- a pure
   * function of the event and what's already stored, so every Raft replica applying the same
   * committed {@code AppendInstanceEvent} entries in the same order ends up with identical pruning
   * decisions, not a separate non-replicated cleanup pass.
   */
  public void putInstanceEvent(InstanceEvent event) {
    writeAtomically(instanceEventFile(event), instanceEventToYaml(event));
    String key = instanceEventsKey(event.deploymentName(), event.instanceIndex());
    instanceEvents.compute(
        key,
        (k, existing) -> {
          List<InstanceEvent> updated = new ArrayList<>(existing == null ? List.of() : existing);
          updated.add(event);
          while (updated.size() > MAX_EVENTS_PER_INSTANCE) {
            InstanceEvent oldest = updated.remove(0);
            deleteQuietly(instanceEventFile(oldest));
          }
          return List.copyOf(updated);
        });
  }

  /** Newest-first -- the natural read order for a timeline, matching {@code /logs}' own tail. */
  public List<InstanceEvent> listInstanceEvents(String deploymentName, int instanceIndex) {
    List<InstanceEvent> events =
        instanceEvents.getOrDefault(instanceEventsKey(deploymentName, instanceIndex), List.of());
    List<InstanceEvent> reversed = new ArrayList<>(events);
    Collections.reverse(reversed);
    return List.copyOf(reversed);
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
    writeAtomically(auditEventFile(event), auditEventToYaml(event));
    synchronized (auditEventsLock) {
      auditEvents.add(event);
      while (auditEvents.size() > MAX_AUDIT_EVENTS) {
        AuditEvent oldest = auditEvents.remove(0);
        deleteQuietly(auditEventFile(oldest));
      }
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
    writeAtomically(controllerRevisionFile(revision), controllerRevisionToYaml(revision));
    String key = ControllerRevision.revisionKey(revision.workloadKind(), revision.name());
    controllerRevisions.compute(
        key,
        (k, existing) -> {
          List<ControllerRevision> updated =
              new ArrayList<>(existing == null ? List.of() : existing);
          updated.add(revision);
          while (updated.size() > MAX_REVISIONS_PER_WORKLOAD) {
            ControllerRevision oldest = updated.remove(0);
            deleteQuietly(controllerRevisionFile(oldest));
          }
          return List.copyOf(updated);
        });
  }

  /** Newest-first, matching {@link #listInstanceEvents}'s own timeline read order. */
  public List<ControllerRevision> listControllerRevisions(String workloadKind, String name) {
    List<ControllerRevision> revisions =
        controllerRevisions.getOrDefault(
            ControllerRevision.revisionKey(workloadKind, name), List.of());
    List<ControllerRevision> reversed = new ArrayList<>(revisions);
    Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  public Optional<ControllerRevision> getControllerRevision(
      String workloadKind, String name, int revision) {
    return controllerRevisions
        .getOrDefault(ControllerRevision.revisionKey(workloadKind, name), List.of())
        .stream()
        .filter(r -> r.revision() == revision)
        .findFirst();
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
        List.copyOf(assignments.values()),
        List.copyOf(jobSpecs.values()),
        List.copyOf(jobRuns.values()),
        Map.copyOf(jobPhases),
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
        instanceEvents.values().stream().flatMap(List::stream).toList(),
        auditEventsSnapshotOrder(),
        List.copyOf(services.values()),
        List.copyOf(networkPolicies.values()),
        controllerRevisions.values().stream().flatMap(List::stream).toList());
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
    List.copyOf(deployments.keySet()).forEach(this::removeDeployment);
    List.copyOf(services.keySet()).forEach(this::removeService);
    List.copyOf(networkPolicies.keySet()).forEach(this::removeNetworkPolicy);
    List.copyOf(assignments.values())
        .forEach(a -> removeAssignment(a.deploymentName(), a.instanceIndex()));
    List.copyOf(jobRuns.values()).forEach(r -> removeJobRun(r.jobName(), r.attempt()));
    List.copyOf(jobSpecs.keySet()).forEach(this::removeJobSpec);
    List.copyOf(cronJobSpecs.keySet()).forEach(this::removeCronJobSpec);
    List.copyOf(daemonSetAssignments.values())
        .forEach(a -> removeDaemonSetAssignment(a.daemonSetName(), a.nodeId()));
    List.copyOf(daemonSetSpecs.keySet()).forEach(this::removeDaemonSetSpec);
    List.copyOf(statefulSetAssignments.values())
        .forEach(a -> removeStatefulSetAssignment(a.statefulSetName(), a.instanceIndex()));
    List.copyOf(statefulSetSpecs.keySet()).forEach(this::removeStatefulSetSpec);
    List.copyOf(statefulSetIndexNodes.keySet())
        .forEach(
            key -> {
              int hash = key.lastIndexOf('#');
              removeStatefulSetIndexNode(
                  key.substring(0, hash), Integer.parseInt(key.substring(hash + 1)));
            });
    List.copyOf(nodeRegistrations.keySet()).forEach(this::removeNodeRegistration);
    List.copyOf(tenants.keySet()).forEach(this::removeTenant);
    List.copyOf(quotaViolations.keySet()).forEach(name -> putQuotaViolation(name, false));
    List.copyOf(nodeCordons.keySet()).forEach(nodeId -> putNodeCordon(nodeId, false));
    List.copyOf(configEntries.values()).forEach(e -> removeConfigEntry(e.tenantId(), e.key()));
    List.copyOf(roles.keySet()).forEach(this::removeRole);
    List.copyOf(roleBindings.keySet()).forEach(this::removeRoleBinding);
    List.copyOf(accounts.keySet()).forEach(this::removeAccount);
    List.copyOf(reconcilerInstanceStates.values())
        .forEach(s -> removeReconcilerInstanceState(s.deploymentName(), s.instanceIndex()));
    clearAllInstanceEvents();
    clearAllAuditEvents();
    clearAllControllerRevisions();

    snapshot.deployments().forEach(this::putDeployment);
    snapshot.assignments().forEach(this::putAssignment);
    snapshot.jobSpecs().forEach(this::putJobSpec);
    snapshot.jobRuns().forEach(this::putJobRun);
    snapshot.jobPhases().forEach(this::putJobPhase);
    snapshot.cronJobSpecs().forEach(this::putCronJobSpec);
    snapshot.cronJobLastSchedule().forEach(this::putCronJobLastSchedule);
    snapshot.daemonSetSpecs().forEach(this::putDaemonSetSpec);
    snapshot.daemonSetAssignments().forEach(this::putDaemonSetAssignment);
    snapshot
        .rollingDaemonSetNodes()
        .forEach(
            (daemonSetName, nodeIds) ->
                nodeIds.forEach(nodeId -> addRollingDaemonSetNode(daemonSetName, nodeId)));
    snapshot.statefulSetSpecs().forEach(this::putStatefulSetSpec);
    snapshot.statefulSetAssignments().forEach(this::putStatefulSetAssignment);
    snapshot.rollingStatefulSetIndices().forEach(this::putRollingStatefulSetIndex);
    snapshot
        .statefulSetIndexNodes()
        .forEach(
            (key, nodeId) -> {
              int hash = key.lastIndexOf('#');
              putStatefulSetIndexNode(
                  key.substring(0, hash), Integer.parseInt(key.substring(hash + 1)), nodeId);
            });
    snapshot.nodeRegistrations().forEach(this::putNodeRegistration);
    snapshot
        .rollingIndices()
        .forEach(
            (deploymentName, indices) ->
                indices.forEach(index -> addRollingIndex(deploymentName, index)));
    snapshot
        .surgeIndices()
        .forEach(
            (deploymentName, indices) ->
                indices.forEach(
                    (surgeIndex, targetIndex) ->
                        addSurgeIndex(deploymentName, surgeIndex, targetIndex)));
    snapshot.effectiveReplicas().forEach(this::putEffectiveReplicas);
    snapshot.tenants().forEach(this::putTenant);
    snapshot.quotaViolatingDeployments().forEach(name -> putQuotaViolation(name, true));
    snapshot.cordonedNodes().forEach(nodeId -> putNodeCordon(nodeId, true));
    snapshot.configEntries().forEach(this::putConfigEntry);
    snapshot.roles().forEach(this::putRole);
    snapshot.roleBindings().forEach(this::putRoleBinding);
    snapshot.accounts().forEach(this::putAccount);
    snapshot.reconcilerInstanceStates().forEach(this::putReconcilerInstanceState);
    // Oldest-first per instance, matching how putInstanceEvent's own pruning expects to see them
    // -- StateSnapshot#instanceEvents preserves that order (see snapshot() above), and replaying
    // in the same order here reproduces identical per-instance retention-cap pruning decisions.
    snapshot.instanceEvents().forEach(this::putInstanceEvent);
    // Same reasoning, cluster-wide: StateSnapshot#auditEvents is oldest-first (see
    // auditEventsSnapshotOrder() above), reproducing identical MAX_AUDIT_EVENTS pruning on replay.
    snapshot.auditEvents().forEach(this::putAuditEvent);
    snapshot.services().forEach(this::putService);
    snapshot.networkPolicies().forEach(this::putNetworkPolicy);
    // Oldest-first, matching how putControllerRevision's own pruning expects to see them -- same
    // reasoning as the instanceEvents/auditEvents replay just above.
    snapshot.controllerRevisions().forEach(this::putControllerRevision);
  }

  /**
   * Full wipe used only by {@link #restoreFromSnapshot} -- unlike every other resource kind here,
   * instance events have no public per-key {@code remove}; pruning is deliberately internal to
   * {@link #putInstanceEvent} only, not a capability callers reach for directly.
   */
  private void clearAllInstanceEvents() {
    for (List<InstanceEvent> events : instanceEvents.values()) {
      events.forEach(e -> deleteQuietly(instanceEventFile(e)));
    }
    instanceEvents.clear();
  }

  /** Same rationale as {@link #clearAllInstanceEvents}, for the cluster-wide audit trail. */
  private void clearAllAuditEvents() {
    synchronized (auditEventsLock) {
      auditEvents.forEach(e -> deleteQuietly(auditEventFile(e)));
      auditEvents.clear();
    }
  }

  /** Same rationale as {@link #clearAllInstanceEvents}, for revision history. */
  private void clearAllControllerRevisions() {
    for (List<ControllerRevision> revisions : controllerRevisions.values()) {
      revisions.forEach(r -> deleteQuietly(controllerRevisionFile(r)));
    }
    controllerRevisions.clear();
  }

  // ---- disk layout ----

  private Path deploymentsDir() {
    return root.resolve("deployments");
  }

  private Path assignmentsDir() {
    return root.resolve("assignments");
  }

  private Path nodesDir() {
    return root.resolve("nodes");
  }

  private Path rollingDir() {
    return root.resolve("rolling");
  }

  private Path rollingFile(String deploymentName, int instanceIndex) {
    return rollingDir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
  }

  private Path surgeDir() {
    return root.resolve("surge");
  }

  private Path surgeFile(String deploymentName, int surgeIndex) {
    return surgeDir().resolve(deploymentName).resolve(surgeIndex + ".yaml");
  }

  private Path autoscaleDir() {
    return root.resolve("autoscale");
  }

  private Path effectiveReplicasFile(String deploymentName) {
    return autoscaleDir().resolve(deploymentName + ".yaml");
  }

  private Path deploymentFile(String name) {
    return deploymentsDir().resolve(name + ".yaml");
  }

  private Path servicesDir() {
    return root.resolve("services");
  }

  private Path serviceFile(String name) {
    return servicesDir().resolve(name + ".yaml");
  }

  private Path networkPoliciesDir() {
    return root.resolve("networkpolicies");
  }

  private Path networkPolicyFile(String name) {
    return networkPoliciesDir().resolve(name + ".yaml");
  }

  private Path assignmentFile(String deploymentName, int instanceIndex) {
    return assignmentsDir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
  }

  private Path jobsDir() {
    return root.resolve("jobs");
  }

  private Path jobFile(String name) {
    return jobsDir().resolve(name + ".yaml");
  }

  private Path jobRunsDir() {
    return root.resolve("jobruns");
  }

  private Path jobRunFile(String jobName, int attempt) {
    return jobRunsDir().resolve(jobName).resolve(attempt + ".yaml");
  }

  private static String jobRunKey(String jobName, int attempt) {
    return jobName + "#" + attempt;
  }

  private Path jobPhasesDir() {
    return root.resolve("jobphases");
  }

  private Path jobPhaseFile(String jobName) {
    return jobPhasesDir().resolve(jobName + ".yaml");
  }

  private Path cronJobsDir() {
    return root.resolve("cronjobs");
  }

  private Path cronJobFile(String name) {
    return cronJobsDir().resolve(name + ".yaml");
  }

  private Path cronJobLastScheduleDir() {
    return root.resolve("cronjobschedule");
  }

  private Path cronJobLastScheduleFile(String name) {
    return cronJobLastScheduleDir().resolve(name + ".yaml");
  }

  private Path daemonSetsDir() {
    return root.resolve("daemonsets");
  }

  private Path daemonSetFile(String name) {
    return daemonSetsDir().resolve(name + ".yaml");
  }

  private Path daemonSetAssignmentsDir() {
    return root.resolve("daemonsetassignments");
  }

  private Path daemonSetAssignmentFile(String daemonSetName, String nodeId) {
    return daemonSetAssignmentsDir().resolve(daemonSetName).resolve(nodeId + ".yaml");
  }

  private static String daemonSetAssignmentKey(String daemonSetName, String nodeId) {
    return daemonSetName + "#" + nodeId;
  }

  private Path rollingDaemonSetDir() {
    return root.resolve("rollingdaemonset");
  }

  private Path rollingDaemonSetFile(String daemonSetName, String nodeId) {
    return rollingDaemonSetDir().resolve(daemonSetName).resolve(nodeId + ".yaml");
  }

  private Path statefulSetsDir() {
    return root.resolve("statefulsets");
  }

  private Path statefulSetFile(String name) {
    return statefulSetsDir().resolve(name + ".yaml");
  }

  private Path statefulSetAssignmentsDir() {
    return root.resolve("statefulsetassignments");
  }

  private Path statefulSetAssignmentFile(String statefulSetName, int instanceIndex) {
    return statefulSetAssignmentsDir().resolve(statefulSetName).resolve(instanceIndex + ".yaml");
  }

  private static String statefulSetAssignmentKey(String statefulSetName, int instanceIndex) {
    return statefulSetName + "#" + instanceIndex;
  }

  private Path rollingStatefulSetDir() {
    return root.resolve("rollingstatefulset");
  }

  private Path rollingStatefulSetFile(String statefulSetName) {
    return rollingStatefulSetDir().resolve(statefulSetName + ".yaml");
  }

  private Path statefulSetIndexNodesDir() {
    return root.resolve("statefulsetindexnodes");
  }

  private Path statefulSetIndexNodeFile(String statefulSetName, int instanceIndex) {
    return statefulSetIndexNodesDir().resolve(statefulSetName).resolve(instanceIndex + ".yaml");
  }

  private Path registrationFile(String nodeId) {
    return nodesDir().resolve(nodeId).resolve("registration.yaml");
  }

  private Path heartbeatFile(String nodeId) {
    return nodesDir().resolve(nodeId).resolve("heartbeat.yaml");
  }

  private static String assignmentKey(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }

  private Path tenantsDir() {
    return root.resolve("tenants");
  }

  private Path tenantFile(String id) {
    return tenantsDir().resolve(id + ".yaml");
  }

  private Path quotaDir() {
    return root.resolve("quota");
  }

  private Path quotaFile(String deploymentName) {
    return quotaDir().resolve(deploymentName + ".yaml");
  }

  private Path cordonDir() {
    return root.resolve("cordon");
  }

  private Path cordonFile(String nodeId) {
    return cordonDir().resolve(nodeId + ".yaml");
  }

  private Path configDir() {
    return root.resolve("config");
  }

  private Path rolesDir() {
    return root.resolve("roles");
  }

  private Path roleFile(String name) {
    return rolesDir().resolve(name + ".yaml");
  }

  private Path roleBindingsDir() {
    return root.resolve("rolebindings");
  }

  private Path roleBindingFile(String id) {
    return roleBindingsDir().resolve(id + ".yaml");
  }

  private Path accountsDir() {
    return root.resolve("accounts");
  }

  private Path accountFile(String username) {
    return accountsDir().resolve(username + ".yaml");
  }

  private Path reconcilerStateDir() {
    return root.resolve("reconciler");
  }

  private Path reconcilerStateFile(String deploymentName, int instanceIndex) {
    return reconcilerStateDir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
  }

  private static String reconcilerStateKey(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }

  private Path instanceEventsDir() {
    return root.resolve("events");
  }

  /**
   * One file per event, named by its own generated id -- unique by construction, no ordering baked
   * into the filename since read order comes from {@code occurredAtEpochMilli} instead.
   */
  private Path instanceEventFile(InstanceEvent event) {
    return instanceEventsDir()
        .resolve(event.deploymentName())
        .resolve(String.valueOf(event.instanceIndex()))
        .resolve(event.id() + ".yaml");
  }

  private static String instanceEventsKey(String deploymentName, int instanceIndex) {
    return deploymentName + "#" + instanceIndex;
  }

  private Path controllerRevisionsDir() {
    return root.resolve("controller-revisions");
  }

  /**
   * One file per revision, named by its own revision number -- unique and orderable by construction
   * within {@code (workloadKind, name)}, unlike {@link #instanceEventFile}'s generated-id naming.
   */
  private Path controllerRevisionFile(ControllerRevision revision) {
    return controllerRevisionsDir()
        .resolve(revision.workloadKind())
        .resolve(revision.name())
        .resolve(revision.revision() + ".yaml");
  }

  private Path auditEventsDir() {
    return root.resolve("audit");
  }

  /**
   * One flat file per event, named by its own generated id -- unlike {@link #instanceEventFile},
   * there's no natural per-key subdirectory for a cluster-wide trail with no single-key scope.
   */
  private Path auditEventFile(AuditEvent event) {
    return auditEventsDir().resolve(event.id() + ".yaml");
  }

  private Path configFile(String tenantId, String key) {
    // Config/secret keys are arbitrary text and may contain characters unsafe in a filename (or
    // even path separators); base64url-encoding the key -- not the tenantId, which the existing
    // node-id/deployment-name directory-naming precedent already assumes is filesystem-safe --
    // keeps this store's "one small file per resource" shape without adding a validation rule
    // config keys never needed before this.
    String encodedKey =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    return configDir().resolve(tenantId).resolve(encodedKey + ".yaml");
  }

  private static String configKey(String tenantId, String key) {
    return tenantId + "#" + key;
  }

  private void loadAll() {
    loadEach(
        deploymentsDir(),
        "*.yaml",
        file -> {
          DeploymentSpec spec =
              DeploymentManifestParser.parse(new ByteArrayInputStream(read(file)));
          deployments.put(spec.name(), spec);
        });
    loadEach(
        servicesDir(),
        "*.yaml",
        file -> {
          ServiceSpec spec = serviceSpecFromMap(loadMap(file));
          services.put(spec.name(), spec);
        });
    loadEach(
        networkPoliciesDir(),
        "*.yaml",
        file -> {
          NetworkPolicySpec spec = networkPolicySpecFromMap(loadMap(file));
          networkPolicies.put(spec.name(), spec);
        });
    loadEach(
        assignmentsDir(),
        "*/*.yaml",
        file -> {
          InstanceAssignment assignment = assignmentFromMap(loadMap(file));
          assignments.put(
              assignmentKey(assignment.deploymentName(), assignment.instanceIndex()), assignment);
        });
    loadEach(
        jobsDir(),
        "*.yaml",
        file -> {
          JobSpec spec = JobManifestParser.parse(new ByteArrayInputStream(read(file)));
          jobSpecs.put(spec.name(), spec);
        });
    loadEach(
        jobRunsDir(),
        "*/*.yaml",
        file -> {
          JobRun run = jobRunFromMap(loadMap(file));
          jobRuns.put(jobRunKey(run.jobName(), run.attempt()), run);
        });
    loadEach(
        jobPhasesDir(),
        "*.yaml",
        file -> {
          String jobName = fileNameWithoutYamlSuffix(file);
          jobPhases.put(jobName, jobPhaseFromMap(loadMap(file)));
        });
    loadEach(
        cronJobsDir(),
        "*.yaml",
        file -> {
          CronJobSpec spec = CronJobManifestParser.parse(new ByteArrayInputStream(read(file)));
          cronJobSpecs.put(spec.name(), spec);
        });
    loadEach(
        cronJobLastScheduleDir(),
        "*.yaml",
        file -> {
          String name = fileNameWithoutYamlSuffix(file);
          cronJobLastSchedule.put(name, cronJobLastScheduleFromMap(loadMap(file)));
        });
    loadEach(
        daemonSetsDir(),
        "*.yaml",
        file -> {
          DaemonSetSpec spec = DaemonSetManifestParser.parse(new ByteArrayInputStream(read(file)));
          daemonSetSpecs.put(spec.name(), spec);
        });
    loadEach(
        daemonSetAssignmentsDir(),
        "*/*.yaml",
        file -> {
          DaemonSetAssignment assignment = daemonSetAssignmentFromMap(loadMap(file));
          daemonSetAssignments.put(
              daemonSetAssignmentKey(assignment.daemonSetName(), assignment.nodeId()), assignment);
        });
    loadEach(
        rollingDaemonSetDir(),
        "*/*.yaml",
        file -> {
          Map<?, ?> map = loadMap(file);
          rollingDaemonSetNodes
              .computeIfAbsent(
                  (String) map.get("daemonSetName"), key -> ConcurrentHashMap.newKeySet())
              .add((String) map.get("nodeId"));
        });
    loadEach(
        statefulSetsDir(),
        "*.yaml",
        file -> {
          StatefulSetSpec spec =
              StatefulSetManifestParser.parse(new ByteArrayInputStream(read(file)));
          statefulSetSpecs.put(spec.name(), spec);
        });
    loadEach(
        statefulSetAssignmentsDir(),
        "*/*.yaml",
        file -> {
          StatefulSetAssignment assignment = statefulSetAssignmentFromMap(loadMap(file));
          statefulSetAssignments.put(
              statefulSetAssignmentKey(assignment.statefulSetName(), assignment.instanceIndex()),
              assignment);
        });
    loadEach(
        rollingStatefulSetDir(),
        "*.yaml",
        file -> {
          String statefulSetName = fileNameWithoutYamlSuffix(file);
          Map<?, ?> map = loadMap(file);
          rollingStatefulSetIndices.put(
              statefulSetName, ((Number) map.get("instanceIndex")).intValue());
        });
    loadEach(
        statefulSetIndexNodesDir(),
        "*/*.yaml",
        file -> {
          Map<?, ?> map = loadMap(file);
          String statefulSetName = (String) map.get("statefulSetName");
          int instanceIndex = ((Number) map.get("instanceIndex")).intValue();
          statefulSetIndexNodes.put(
              statefulSetAssignmentKey(statefulSetName, instanceIndex), (String) map.get("nodeId"));
        });
    loadEach(
        nodesDir(),
        "*/registration.yaml",
        file -> {
          NodeRegistration registration = registrationFromMap(loadMap(file));
          nodeRegistrations.put(registration.nodeId(), registration);
        });
    loadEach(
        nodesDir(),
        "*/heartbeat.yaml",
        file -> {
          ObservedHeartbeat observed = heartbeatFromMap(loadMap(file));
          nodeHeartbeats.put(observed.heartbeat().nodeId(), observed);
        });
    loadEach(
        rollingDir(),
        "*/*.yaml",
        file -> {
          Map<?, ?> map = loadMap(file);
          rollingIndices
              .computeIfAbsent(
                  (String) map.get("deploymentName"), key -> ConcurrentHashMap.newKeySet())
              .add(((Number) map.get("instanceIndex")).intValue());
        });
    loadEach(
        surgeDir(),
        "*/*.yaml",
        file -> {
          Map<?, ?> map = loadMap(file);
          surgeIndices
              .computeIfAbsent((String) map.get("deploymentName"), key -> new ConcurrentHashMap<>())
              .put(
                  ((Number) map.get("surgeIndex")).intValue(),
                  ((Number) map.get("targetIndex")).intValue());
        });
    loadEach(
        autoscaleDir(),
        "*.yaml",
        file -> {
          String deploymentName = fileNameWithoutYamlSuffix(file);
          Map<?, ?> map = loadMap(file);
          effectiveReplicas.put(deploymentName, ((Number) map.get("replicas")).intValue());
        });
    loadEach(
        tenantsDir(),
        "*.yaml",
        file -> {
          Tenant tenant = tenantFromMap(loadMap(file));
          tenants.put(tenant.id(), tenant);
        });
    loadEach(
        quotaDir(),
        "*.yaml",
        file -> {
          String deploymentName = fileNameWithoutYamlSuffix(file);
          quotaViolations.put(deploymentName, Boolean.TRUE);
        });
    loadEach(
        cordonDir(),
        "*.yaml",
        file -> {
          String nodeId = fileNameWithoutYamlSuffix(file);
          nodeCordons.put(nodeId, Boolean.TRUE);
        });
    loadEach(
        configDir(),
        "*/*.yaml",
        file -> {
          ConfigEntry entry = configEntryFromMap(loadMap(file));
          configEntries.put(configKey(entry.tenantId(), entry.key()), entry);
        });
    loadEach(
        rolesDir(),
        "*.yaml",
        file -> {
          Role role = roleFromMap(loadMap(file));
          roles.put(role.name(), role);
        });
    loadEach(
        roleBindingsDir(),
        "*.yaml",
        file -> {
          RoleBinding binding = roleBindingFromMap(loadMap(file));
          roleBindings.put(binding.id(), binding);
        });
    loadEach(
        accountsDir(),
        "*.yaml",
        file -> {
          Account account = accountFromMap(loadMap(file));
          accounts.put(account.username(), account);
        });
    loadEach(
        reconcilerStateDir(),
        "*/*.yaml",
        file -> {
          ReconcilerInstanceState state = reconcilerInstanceStateFromMap(loadMap(file));
          reconcilerInstanceStates.put(
              reconcilerStateKey(state.deploymentName(), state.instanceIndex()), state);
        });
    loadEach(
        instanceEventsDir(),
        "*/*/*.yaml",
        file -> {
          InstanceEvent event = instanceEventFromMap(loadMap(file));
          String key = instanceEventsKey(event.deploymentName(), event.instanceIndex());
          instanceEvents.compute(
              key,
              (k, existing) -> {
                List<InstanceEvent> updated =
                    new ArrayList<>(existing == null ? List.of() : existing);
                updated.add(event);
                return updated;
              });
        });
    // Sort each instance's loaded events by occurrence time -- loadEach's directory-stream order
    // is not guaranteed to match append order, but putInstanceEvent's own pruning assumes the
    // in-memory list is oldest-first (it prunes from the front). Ties (same millisecond) are
    // broken by id for a stable, deterministic order across replicas that saw the same events.
    for (Map.Entry<String, List<InstanceEvent>> entry : instanceEvents.entrySet()) {
      List<InstanceEvent> sorted = new ArrayList<>(entry.getValue());
      sorted.sort(
          Comparator.comparingLong(InstanceEvent::occurredAtEpochMilli)
              .thenComparing(InstanceEvent::id));
      entry.setValue(List.copyOf(sorted));
    }
    loadEach(
        auditEventsDir(),
        "*.yaml",
        file -> {
          AuditEvent event = auditEventFromMap(loadMap(file));
          synchronized (auditEventsLock) {
            auditEvents.add(event);
          }
        });
    // Same reasoning as the instance-event sort just above: loadEach's directory-stream order
    // isn't append order, but putAuditEvent's own pruning assumes the in-memory list is
    // oldest-first (it prunes from the front).
    synchronized (auditEventsLock) {
      auditEvents.sort(
          Comparator.comparingLong(AuditEvent::occurredAtEpochMilli).thenComparing(AuditEvent::id));
    }
    loadEach(
        controllerRevisionsDir(),
        "*/*/*.yaml",
        file -> {
          ControllerRevision revision = controllerRevisionFromMap(loadMap(file));
          String key = ControllerRevision.revisionKey(revision.workloadKind(), revision.name());
          controllerRevisions.compute(
              key,
              (k, existing) -> {
                List<ControllerRevision> updated =
                    new ArrayList<>(existing == null ? List.of() : existing);
                updated.add(revision);
                return updated;
              });
        });
    // Sort each workload's loaded revisions by revision number -- loadEach's directory-stream
    // order isn't guaranteed to match append order, but putControllerRevision's own pruning
    // assumes the in-memory list is oldest-first (it prunes from the front).
    for (Map.Entry<String, List<ControllerRevision>> entry : controllerRevisions.entrySet()) {
      List<ControllerRevision> sorted = new ArrayList<>(entry.getValue());
      sorted.sort(Comparator.comparingInt(ControllerRevision::revision));
      entry.setValue(List.copyOf(sorted));
    }
  }

  private interface FileLoader {
    void load(Path file) throws IOException;
  }

  private static void loadEach(Path dir, String glob, FileLoader loader) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var stream = Files.newDirectoryStream(dir, glob.contains("/") ? "*" : glob)) {
      for (Path entry : stream) {
        if (glob.contains("/")) {
          // one level of subdirectories, matching the remaining glob segment
          String childGlob = glob.substring(glob.indexOf('/') + 1);
          if (Files.isDirectory(entry)) {
            loadEach(entry, childGlob, loader);
          }
        } else if (Files.isRegularFile(entry)) {
          loader.load(entry);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<?, ?> loadMap(Path file) {
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object raw;
    try {
      raw = yaml.load(new ByteArrayInputStream(read(file)));
    } catch (RuntimeException e) {
      throw new IllegalStateException("corrupt state store file " + file, e);
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalStateException("expected a YAML mapping in " + file);
    }
    return map;
  }

  // Every loadEach caller below keys its in-memory map by the file's own basename (minus the
  // .yaml suffix) rather than by a field inside the YAML content -- getFileName() can only return
  // null for a zero-element path, which never happens for a Path yielded by a directory-stream
  // walk, but centralizing the null-guard here (rather than repeating it at each of the four
  // call sites) is what actually satisfies that in one place.
  private static String fileNameWithoutYamlSuffix(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a regular file, got " + file);
    }
    return fileName.toString().replaceFirst("\\.yaml$", "");
  }

  private static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeAtomically(Path target, String content) {
    AtomicFiles.writeAtomically(target, content);
  }

  private static void deleteQuietly(Path file) {
    AtomicFiles.deleteQuietly(file);
  }

  // ---- YAML (de)serialization: hand-rolled Map<->record mapping, same posture as
  // DeploymentManifestParser -- these are our own written files, not user-facing input, but kept
  // just as defensive (SafeConstructor on read) for consistency. ----

  /**
   * The {@code {name, version}} shape a {@link ModuleId} is written as, everywhere it's embedded.
   */
  private static Map<String, Object> moduleIdToYamlMap(ModuleId moduleId) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", moduleId.name());
    map.put("version", moduleId.version().toString());
    return map;
  }

  /** The inverse of {@link #moduleIdToYamlMap}. */
  private static ModuleId moduleIdFromYamlMap(Map<?, ?> map) {
    return new ModuleId((String) map.get("name"), Version.parse((String) map.get("version")));
  }

  /**
   * {@code map.get(key)}, null-safe into an {@link Optional} instead of a raw possibly-null cast.
   */
  private static Optional<String> optionalString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? Optional.empty() : Optional.of((String) value);
  }

  private static String deploymentToYaml(DeploymentSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    // Every manifest carries kind: now -- this is our own round-trip write, not operator input,
    // but DeploymentManifestParser.parseRoot ignores unknown/extra keys regardless, so including
    // it costs nothing and keeps this file self-describing on disk.
    root.put("kind", "Deployment");
    root.put("name", spec.name());
    root.put("module", moduleIdToYamlMap(spec.moduleId()));
    // Omitted when blank: absent-on-disk round-trips through the manifest parser back to the
    // resolve-from-registry state, while an explicitly blank value would be rejected there.
    if (!spec.artifactPath().isBlank()) {
      root.put("artifactPath", spec.artifactPath());
    }
    root.put("replicas", spec.replicas());
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("antiAffinity", spec.placement().antiAffinityAcrossNodes());
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    root.put("placement", placement);
    spec.autoscale()
        .ifPresent(
            policy -> {
              Map<String, Object> autoscale = new LinkedHashMap<>();
              autoscale.put("minReplicas", policy.minReplicas());
              autoscale.put("maxReplicas", policy.maxReplicas());
              autoscale.put("targetCpuUtilizationPercent", policy.targetCpuUtilizationPercent());
              root.put("autoscale", autoscale);
            });
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    spec.artifactSha256().ifPresent(sha256 -> root.put("artifactSha256", sha256));
    return new Yaml().dump(root);
  }

  private static String jobSpecToYaml(JobSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("kind", "Job");
    root.put("name", spec.name());
    root.put("module", moduleIdToYamlMap(spec.moduleId()));
    // Omitted when blank: absent-on-disk round-trips through the manifest parser back to the
    // resolve-from-registry state, while an explicitly blank value would be rejected there.
    if (!spec.artifactPath().isBlank()) {
      root.put("artifactPath", spec.artifactPath());
    }
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("antiAffinity", spec.placement().antiAffinityAcrossNodes());
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    root.put("placement", placement);
    spec.activeDeadline().ifPresent(d -> root.put("activeDeadlineSeconds", d.toSeconds()));
    root.put("backoffLimit", spec.backoffLimit());
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    spec.artifactSha256().ifPresent(sha256 -> root.put("artifactSha256", sha256));
    return new Yaml().dump(root);
  }

  private static String jobRunToYaml(JobRun run) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("jobName", run.jobName());
    root.put("attempt", run.attempt());
    root.put("nodeId", run.nodeId());
    root.put("moduleId", moduleIdToYamlMap(run.moduleId()));
    root.put("artifactPath", run.artifactPath());
    root.put("startedAt", run.startedAt().toString());
    return new Yaml().dump(root);
  }

  private static JobRun jobRunFromMap(Map<?, ?> map) {
    ModuleId moduleId = moduleIdFromYamlMap((Map<?, ?>) map.get("moduleId"));
    return new JobRun(
        (String) map.get("jobName"),
        ((Number) map.get("attempt")).intValue(),
        (String) map.get("nodeId"),
        moduleId,
        (String) map.get("artifactPath"),
        Instant.parse((String) map.get("startedAt")));
  }

  private static String jobPhaseToYaml(JobPhase phase) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("phase", phase.name());
    return new Yaml().dump(root);
  }

  private static JobPhase jobPhaseFromMap(Map<?, ?> map) {
    return JobPhase.valueOf((String) map.get("phase"));
  }

  private static String cronJobSpecToYaml(CronJobSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("kind", "CronJob");
    root.put("name", spec.name());
    root.put("schedule", spec.schedule());
    Map<String, Object> template = new LinkedHashMap<>();
    template.put("module", moduleIdToYamlMap(spec.jobTemplate().moduleId()));
    // Omitted when blank, same reason as deploymentToYaml's own artifactPath handling.
    if (!spec.jobTemplate().artifactPath().isBlank()) {
      template.put("artifactPath", spec.jobTemplate().artifactPath());
    }
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("antiAffinity", spec.jobTemplate().placement().antiAffinityAcrossNodes());
    spec.jobTemplate()
        .placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    template.put("placement", placement);
    spec.jobTemplate()
        .activeDeadline()
        .ifPresent(d -> template.put("activeDeadlineSeconds", d.toSeconds()));
    template.put("backoffLimit", spec.jobTemplate().backoffLimit());
    root.put("jobTemplate", template);
    spec.startingDeadline().ifPresent(d -> root.put("startingDeadlineSeconds", d.toSeconds()));
    root.put("concurrencyPolicy", spec.concurrencyPolicy().name());
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    return new Yaml().dump(root);
  }

  private static String cronJobLastScheduleToYaml(Instant lastScheduleTime) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("lastScheduleTime", lastScheduleTime.toString());
    return new Yaml().dump(root);
  }

  private static Instant cronJobLastScheduleFromMap(Map<?, ?> map) {
    return Instant.parse((String) map.get("lastScheduleTime"));
  }

  private static String daemonSetSpecToYaml(DaemonSetSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("kind", "DaemonSet");
    root.put("name", spec.name());
    root.put("module", moduleIdToYamlMap(spec.moduleId()));
    // Omitted when blank: absent-on-disk round-trips through the manifest parser back to the
    // resolve-from-registry state, while an explicitly blank value would be rejected there.
    if (!spec.artifactPath().isBlank()) {
      root.put("artifactPath", spec.artifactPath());
    }
    Map<String, Object> placement = new LinkedHashMap<>();
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    root.put("placement", placement);
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    spec.artifactSha256().ifPresent(sha256 -> root.put("artifactSha256", sha256));
    return new Yaml().dump(root);
  }

  private static String daemonSetAssignmentToYaml(DaemonSetAssignment assignment) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("daemonSetName", assignment.daemonSetName());
    root.put("nodeId", assignment.nodeId());
    root.put("moduleId", moduleIdToYamlMap(assignment.moduleId()));
    root.put("artifactPath", assignment.artifactPath());
    return new Yaml().dump(root);
  }

  private static DaemonSetAssignment daemonSetAssignmentFromMap(Map<?, ?> map) {
    ModuleId moduleId = moduleIdFromYamlMap((Map<?, ?>) map.get("moduleId"));
    return new DaemonSetAssignment(
        (String) map.get("daemonSetName"),
        (String) map.get("nodeId"),
        moduleId,
        (String) map.get("artifactPath"));
  }

  // daemonSetName is also embedded in the file's own path (rollingDaemonSetFile), but written into
  // the YAML body too so loadAll's reader never needs to derive it from Path.getParent() (which
  // SpotBugs correctly flags as nullable in general, even though it never actually is here) -- the
  // same "don't trust path structure when the data can just say it" posture
  // statefulSetIndexNodeToYaml
  // already establishes.
  private static String rollingDaemonSetNodeToYaml(String daemonSetName, String nodeId) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("daemonSetName", daemonSetName);
    root.put("nodeId", nodeId);
    return new Yaml().dump(root);
  }

  private static String statefulSetSpecToYaml(StatefulSetSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("kind", "StatefulSet");
    root.put("name", spec.name());
    root.put("module", moduleIdToYamlMap(spec.moduleId()));
    // Omitted when blank: absent-on-disk round-trips through the manifest parser back to the
    // resolve-from-registry state, while an explicitly blank value would be rejected there.
    if (!spec.artifactPath().isBlank()) {
      root.put("artifactPath", spec.artifactPath());
    }
    root.put("replicas", spec.replicas());
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("antiAffinity", spec.placement().antiAffinityAcrossNodes());
    spec.placement()
        .requiredNodeLabels()
        .ifPresent(labels -> placement.put("requiredLabels", new ArrayList<>(labels)));
    root.put("placement", placement);
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    spec.artifactSha256().ifPresent(sha256 -> root.put("artifactSha256", sha256));
    return new Yaml().dump(root);
  }

  private static String statefulSetAssignmentToYaml(StatefulSetAssignment assignment) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("statefulSetName", assignment.statefulSetName());
    root.put("instanceIndex", assignment.instanceIndex());
    root.put("nodeId", assignment.nodeId());
    root.put("moduleId", moduleIdToYamlMap(assignment.moduleId()));
    root.put("artifactPath", assignment.artifactPath());
    return new Yaml().dump(root);
  }

  private static StatefulSetAssignment statefulSetAssignmentFromMap(Map<?, ?> map) {
    ModuleId moduleId = moduleIdFromYamlMap((Map<?, ?>) map.get("moduleId"));
    return new StatefulSetAssignment(
        (String) map.get("statefulSetName"),
        ((Number) map.get("instanceIndex")).intValue(),
        (String) map.get("nodeId"),
        moduleId,
        (String) map.get("artifactPath"));
  }

  // statefulSetName/instanceIndex are also embedded in the file's own path
  // (statefulSetIndexNodeFile), but written into the YAML body too so loadAll's reader never needs
  // to derive them from Path.getParent() (which SpotBugs correctly flags as nullable in general,
  // even though it never actually is here) -- the same "don't trust path structure when the data
  // can just say it" posture every other *FromMap reader in this class already follows.
  private static String statefulSetIndexNodeToYaml(
      String statefulSetName, int instanceIndex, String nodeId) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("statefulSetName", statefulSetName);
    root.put("instanceIndex", instanceIndex);
    root.put("nodeId", nodeId);
    return new Yaml().dump(root);
  }

  /** Shared with {@link #putRollingStatefulSetIndex} -- StatefulSet's own single-index shape. */
  private static String rollingToYaml(int instanceIndex) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("instanceIndex", instanceIndex);
    return new Yaml().dump(root);
  }

  // deploymentName is also embedded in the file's own path (rollingFile), but written into the
  // YAML body too for the identical Path.getParent()-avoidance reason rollingDaemonSetNodeToYaml's
  // own comment documents -- a distinct method from rollingToYaml above (not an overload) because
  // that one is still shared with putRollingStatefulSetIndex's own single-index-per-name shape,
  // which never needed a deploymentName field in its body.
  private static String rollingIndexToYaml(String deploymentName, int instanceIndex) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", deploymentName);
    root.put("instanceIndex", instanceIndex);
    return new Yaml().dump(root);
  }

  private static String surgeIndexToYaml(String deploymentName, int surgeIndex, int targetIndex) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", deploymentName);
    root.put("surgeIndex", surgeIndex);
    root.put("targetIndex", targetIndex);
    return new Yaml().dump(root);
  }

  private static String effectiveReplicasToYaml(int replicas) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("replicas", replicas);
    return new Yaml().dump(root);
  }

  private static String assignmentToYaml(InstanceAssignment assignment) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", assignment.deploymentName());
    root.put("instanceIndex", assignment.instanceIndex());
    root.put("nodeId", assignment.nodeId());
    root.put("moduleId", moduleIdToYamlMap(assignment.moduleId()));
    root.put("artifactPath", assignment.artifactPath());
    assignment
        .renamedFromInstanceIndex()
        .ifPresent(index -> root.put("renamedFromInstanceIndex", index));
    return new Yaml().dump(root);
  }

  private static InstanceAssignment assignmentFromMap(Map<?, ?> map) {
    ModuleId moduleId = moduleIdFromYamlMap((Map<?, ?>) map.get("moduleId"));
    Object artifactPath = map.get("artifactPath");
    Object renamedFrom = map.get("renamedFromInstanceIndex");
    return new InstanceAssignment(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        (String) map.get("nodeId"),
        moduleId,
        artifactPath == null ? "" : (String) artifactPath,
        renamedFrom == null
            ? OptionalInt.empty()
            : OptionalInt.of(((Number) renamedFrom).intValue()));
  }

  private static String registrationToYaml(NodeRegistration registration) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nodeId", registration.nodeId());
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put(
        "supportedTiers",
        registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
    capabilities.put("labels", List.copyOf(registration.capabilities().labels()));
    root.put("capabilities", capabilities);
    registration.apiAddress().ifPresent(address -> root.put("apiAddress", address));
    return new Yaml().dump(root);
  }

  private static NodeRegistration registrationFromMap(Map<?, ?> root) {
    String nodeId = (String) root.get("nodeId");
    Map<?, ?> capabilities = (Map<?, ?>) root.get("capabilities");
    List<?> tiers = (List<?>) capabilities.get("supportedTiers");
    Set<IsolationTier> supportedTiers =
        tiers.stream()
            .map(t -> IsolationTier.valueOf((String) t))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    // Absent on a snapshot written before labels existed -- default to none rather than fail to
    // load an otherwise-valid, already-persisted registration.
    List<?> rawLabels = (List<?>) capabilities.get("labels");
    Set<String> labels =
        rawLabels == null
            ? Set.of()
            : rawLabels.stream()
                .map(String.class::cast)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    return new NodeRegistration(
        nodeId, new NodeCapabilities(supportedTiers, labels), optionalString(root, "apiAddress"));
  }

  private static String heartbeatToYaml(NodeHeartbeat heartbeat, Instant receivedAt) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("nodeId", heartbeat.nodeId());
    root.put("receivedAt", receivedAt.toString());
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", heartbeat.capacity().totalMemoryBytes());
    capacity.put("assignedMemoryBytes", heartbeat.capacity().assignedMemoryBytes());
    capacity.put("totalCpuMillicores", heartbeat.capacity().totalCpuMillicores());
    capacity.put("assignedCpuMillicores", heartbeat.capacity().assignedCpuMillicores());
    root.put("capacity", capacity);
    List<Map<String, Object>> instances = new ArrayList<>();
    for (InstanceObservation obs : heartbeat.instances()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("deploymentName", obs.deploymentName());
      m.put("instanceIndex", obs.instanceIndex());
      m.put("moduleId", moduleIdToYamlMap(obs.moduleId()));
      m.put("lifecycleState", obs.lifecycleState());
      m.put("alive", obs.alive());
      m.put("ready", obs.ready());
      m.put("requestRatePerSecond", obs.requestRatePerSecond());
      m.put("queueDepth", obs.queueDepth());
      m.put("cpuMillicoresUsed", obs.cpuMillicoresUsed());
      m.put("memoryBytesUsed", obs.memoryBytesUsed());
      m.put("errorRatePerSecond", obs.errorRatePerSecond());
      instances.add(m);
    }
    root.put("instances", instances);
    return new Yaml().dump(root);
  }

  private static Number numberOrDefault(Object value, Number defaultValue) {
    return value instanceof Number number ? number : defaultValue;
  }

  private static ObservedHeartbeat heartbeatFromMap(Map<?, ?> root) {
    String nodeId = (String) root.get("nodeId");
    Instant receivedAt = Instant.parse((String) root.get("receivedAt"));
    Map<?, ?> capacityMap = (Map<?, ?>) root.get("capacity");
    ResourceUsageSnapshot capacity =
        new ResourceUsageSnapshot(
            ((Number) capacityMap.get("totalMemoryBytes")).longValue(),
            ((Number) capacityMap.get("assignedMemoryBytes")).longValue(),
            ((Number) capacityMap.get("totalCpuMillicores")).longValue(),
            ((Number) capacityMap.get("assignedCpuMillicores")).longValue());
    List<?> instancesList = (List<?>) root.get("instances");
    List<InstanceObservation> instances = new ArrayList<>();
    for (Object o : instancesList) {
      Map<?, ?> m = (Map<?, ?>) o;
      ModuleId moduleId = moduleIdFromYamlMap((Map<?, ?>) m.get("moduleId"));
      instances.add(
          new InstanceObservation(
              (String) m.get("deploymentName"),
              ((Number) m.get("instanceIndex")).intValue(),
              moduleId,
              (String) m.get("lifecycleState"),
              (Boolean) m.get("alive"),
              (Boolean) m.get("ready"),
              numberOrDefault(m.get("requestRatePerSecond"), 0.0).doubleValue(),
              numberOrDefault(m.get("queueDepth"), 0).intValue(),
              numberOrDefault(m.get("cpuMillicoresUsed"), 0L).longValue(),
              numberOrDefault(m.get("memoryBytesUsed"), 0L).longValue(),
              numberOrDefault(m.get("errorRatePerSecond"), 0.0).doubleValue()));
    }
    return new ObservedHeartbeat(new NodeHeartbeat(nodeId, capacity, instances), receivedAt);
  }

  private static String tenantToYaml(Tenant tenant) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("id", tenant.id());
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    quota.put("maxInstances", tenant.quota().maxInstances());
    root.put("quota", quota);
    return new Yaml().dump(root);
  }

  private static Tenant tenantFromMap(Map<?, ?> root) {
    Map<?, ?> quotaMap = (Map<?, ?>) root.get("quota");
    ResourceQuota quota =
        new ResourceQuota(
            ((Number) quotaMap.get("maxMemoryBytes")).longValue(),
            ((Number) quotaMap.get("maxCpuMillicores")).longValue(),
            ((Number) quotaMap.get("maxInstances")).intValue());
    return new Tenant((String) root.get("id"), quota);
  }

  private static String serviceSpecToYaml(ServiceSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", spec.name());
    spec.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    root.put("deploymentNames", new ArrayList<>(spec.deploymentNames()));
    root.put("port", spec.port());
    root.put("targetPort", spec.targetPort());
    return new Yaml().dump(root);
  }

  private static ServiceSpec serviceSpecFromMap(Map<?, ?> root) {
    Set<String> deploymentNames = new LinkedHashSet<>();
    for (Object rawName : (List<?>) root.get("deploymentNames")) {
      deploymentNames.add((String) rawName);
    }
    return new ServiceSpec(
        (String) root.get("name"),
        Optional.ofNullable((String) root.get("tenantId")),
        deploymentNames,
        ((Number) root.get("port")).intValue(),
        ((Number) root.get("targetPort")).intValue());
  }

  private static String networkPolicySpecToYaml(NetworkPolicySpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", spec.name());
    root.put("tenantId", spec.tenantId());
    spec.deploymentNames().ifPresent(names -> root.put("deploymentNames", new ArrayList<>(names)));
    root.put("allowedCallerTenantIds", new ArrayList<>(spec.allowedCallerTenantIds()));
    return new Yaml().dump(root);
  }

  private static NetworkPolicySpec networkPolicySpecFromMap(Map<?, ?> root) {
    Optional<Set<String>> deploymentNames = Optional.empty();
    List<?> rawDeploymentNames = (List<?>) root.get("deploymentNames");
    if (rawDeploymentNames != null) {
      Set<String> names = new LinkedHashSet<>();
      for (Object rawName : rawDeploymentNames) {
        names.add((String) rawName);
      }
      deploymentNames = Optional.of(names);
    }
    Set<String> allowedCallerTenantIds = new LinkedHashSet<>();
    for (Object rawTenantId : (List<?>) root.get("allowedCallerTenantIds")) {
      allowedCallerTenantIds.add((String) rawTenantId);
    }
    return new NetworkPolicySpec(
        (String) root.get("name"),
        (String) root.get("tenantId"),
        deploymentNames,
        allowedCallerTenantIds);
  }

  private static String configEntryToYaml(ConfigEntry entry) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("tenantId", entry.tenantId());
    root.put("key", entry.key());
    root.put("value", Base64.getEncoder().encodeToString(entry.value()));
    root.put("encrypted", entry.encrypted());
    return new Yaml().dump(root);
  }

  private static ConfigEntry configEntryFromMap(Map<?, ?> root) {
    byte[] value = Base64.getDecoder().decode((String) root.get("value"));
    return new ConfigEntry(
        (String) root.get("tenantId"),
        (String) root.get("key"),
        value,
        (Boolean) root.get("encrypted"));
  }

  private static String roleToYaml(Role role) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", role.name());
    List<Map<String, Object>> permissions = new ArrayList<>();
    for (Permission p : role.permissions()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("resource", p.resource().name());
      m.put("verb", p.verb().name());
      p.tenantScope().ifPresent(t -> m.put("tenantScope", t));
      permissions.add(m);
    }
    root.put("permissions", permissions);
    return new Yaml().dump(root);
  }

  private static Role roleFromMap(Map<?, ?> root) {
    String name = (String) root.get("name");
    List<?> permissionsList = (List<?>) root.get("permissions");
    Set<Permission> permissions = new LinkedHashSet<>();
    for (Object o : permissionsList) {
      Map<?, ?> m = (Map<?, ?>) o;
      ResourceKind resource = ResourceKind.valueOf((String) m.get("resource"));
      Verb verb = Verb.valueOf((String) m.get("verb"));
      permissions.add(new Permission(resource, verb, optionalString(m, "tenantScope")));
    }
    return new Role(name, permissions);
  }

  private static String roleBindingToYaml(RoleBinding binding) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("id", binding.id());
    root.put("subject", binding.subject());
    root.put("roleName", binding.roleName());
    return new Yaml().dump(root);
  }

  private static RoleBinding roleBindingFromMap(Map<?, ?> root) {
    return new RoleBinding(
        (String) root.get("id"), (String) root.get("subject"), (String) root.get("roleName"));
  }

  private static String accountToYaml(Account account) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("username", account.username());
    root.put("passwordHash", Base64.getEncoder().encodeToString(account.passwordHash()));
    return new Yaml().dump(root);
  }

  private static Account accountFromMap(Map<?, ?> root) {
    byte[] passwordHash = Base64.getDecoder().decode((String) root.get("passwordHash"));
    return new Account((String) root.get("username"), passwordHash);
  }

  private static String reconcilerInstanceStateToYaml(ReconcilerInstanceState state) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("deploymentName", state.deploymentName());
    root.put("instanceIndex", state.instanceIndex());
    root.put("attemptsInWindow", state.attemptsInWindow());
    root.put("windowStartEpochMilli", state.windowStartEpochMilli());
    root.put("nextAllowedAttemptEpochMilli", state.nextAllowedAttemptEpochMilli());
    root.put("pendingRetry", state.pendingRetry());
    root.put("permanentlyFailed", state.permanentlyFailed());
    root.put("firstSeenMissingAtEpochMilli", state.firstSeenMissingAtEpochMilli());
    return new Yaml().dump(root);
  }

  private static ReconcilerInstanceState reconcilerInstanceStateFromMap(Map<?, ?> root) {
    return new ReconcilerInstanceState(
        (String) root.get("deploymentName"),
        ((Number) root.get("instanceIndex")).intValue(),
        ((Number) root.get("attemptsInWindow")).intValue(),
        ((Number) root.get("windowStartEpochMilli")).longValue(),
        ((Number) root.get("nextAllowedAttemptEpochMilli")).longValue(),
        (Boolean) root.get("pendingRetry"),
        (Boolean) root.get("permanentlyFailed"),
        ((Number) root.get("firstSeenMissingAtEpochMilli")).longValue());
  }

  /**
   * The embedded {@code spec} is written by delegating to whichever per-kind manifest-shaped writer
   * already exists ({@link #deploymentToYaml}/{@link #statefulSetSpecToYaml}/{@link
   * #daemonSetSpecToYaml}) rather than inventing a second (de)serialization scheme for the same
   * three record types -- its own full YAML text is embedded as a single string field, round-
   * tripped back through the matching manifest parser in {@link #controllerRevisionFromMap}.
   */
  private static String controllerRevisionToYaml(ControllerRevision revision) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("workloadKind", revision.workloadKind());
    root.put("name", revision.name());
    root.put("revision", revision.revision());
    root.put(
        "specYaml",
        switch (revision.spec()) {
          case DeploymentSpec s -> deploymentToYaml(s);
          case StatefulSetSpec s -> statefulSetSpecToYaml(s);
          case DaemonSetSpec s -> daemonSetSpecToYaml(s);
          default ->
              throw new IllegalStateException(
                  "ControllerRevision cannot embed a " + revision.spec().getClass());
        });
    root.put("createdAtEpochMilli", revision.createdAtEpochMilli());
    revision.rollbackOfRevision().ifPresent(r -> root.put("rollbackOfRevision", r));
    return new Yaml().dump(root);
  }

  private static ControllerRevision controllerRevisionFromMap(Map<?, ?> root) {
    String workloadKind = (String) root.get("workloadKind");
    String name = (String) root.get("name");
    int revision = ((Number) root.get("revision")).intValue();
    String specYaml = (String) root.get("specYaml");
    WorkloadSpec spec =
        switch (workloadKind) {
          case "Deployment" ->
              DeploymentManifestParser.parse(
                  new ByteArrayInputStream(specYaml.getBytes(StandardCharsets.UTF_8)));
          case "StatefulSet" ->
              StatefulSetManifestParser.parse(
                  new ByteArrayInputStream(specYaml.getBytes(StandardCharsets.UTF_8)));
          case "DaemonSet" ->
              DaemonSetManifestParser.parse(
                  new ByteArrayInputStream(specYaml.getBytes(StandardCharsets.UTF_8)));
          default -> throw new IllegalStateException("unknown workloadKind: " + workloadKind);
        };
    long createdAtEpochMilli = ((Number) root.get("createdAtEpochMilli")).longValue();
    OptionalInt rollbackOfRevision =
        root.containsKey("rollbackOfRevision")
            ? OptionalInt.of(((Number) root.get("rollbackOfRevision")).intValue())
            : OptionalInt.empty();
    return new ControllerRevision(
        workloadKind, name, revision, spec, createdAtEpochMilli, rollbackOfRevision);
  }

  private static String instanceEventToYaml(InstanceEvent event) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("id", event.id());
    root.put("deploymentName", event.deploymentName());
    root.put("instanceIndex", event.instanceIndex());
    root.put("kind", event.kind().name());
    root.put("message", event.message());
    event.causeSummary().ifPresent(summary -> root.put("causeSummary", summary));
    root.put("occurredAtEpochMilli", event.occurredAtEpochMilli());
    return new Yaml().dump(root);
  }

  private static InstanceEvent instanceEventFromMap(Map<?, ?> root) {
    return new InstanceEvent(
        (String) root.get("id"),
        (String) root.get("deploymentName"),
        ((Number) root.get("instanceIndex")).intValue(),
        InstanceEventKind.valueOf((String) root.get("kind")),
        (String) root.get("message"),
        optionalString(root, "causeSummary"),
        ((Number) root.get("occurredAtEpochMilli")).longValue());
  }

  private static String auditEventToYaml(AuditEvent event) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("id", event.id());
    root.put("principal", event.principal());
    root.put("groups", List.copyOf(event.groups()));
    root.put("resourceKind", event.resourceKind());
    root.put("verb", event.verb());
    event.tenantId().ifPresent(tenantId -> root.put("tenantId", tenantId));
    event.targetId().ifPresent(targetId -> root.put("targetId", targetId));
    root.put("allowed", event.allowed());
    root.put("occurredAtEpochMilli", event.occurredAtEpochMilli());
    return new Yaml().dump(root);
  }

  private static AuditEvent auditEventFromMap(Map<?, ?> root) {
    List<?> rawGroups = (List<?>) root.get("groups");
    Set<String> groups =
        rawGroups == null
            ? Set.of()
            : rawGroups.stream()
                .map(String.class::cast)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    return new AuditEvent(
        (String) root.get("id"),
        (String) root.get("principal"),
        groups,
        (String) root.get("resourceKind"),
        (String) root.get("verb"),
        optionalString(root, "tenantId"),
        optionalString(root, "targetId"),
        (Boolean) root.get("allowed"),
        ((Number) root.get("occurredAtEpochMilli")).longValue());
  }
}
