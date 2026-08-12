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
import com.gimle.mimir.manifest.DeploymentManifestParser;
import com.gimle.mimir.manifest.DeploymentSpec;
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
  private final Map<String, InstanceAssignment> assignments = new ConcurrentHashMap<>();
  private final Map<String, NodeRegistration> nodeRegistrations = new ConcurrentHashMap<>();
  private final Map<String, ObservedHeartbeat> nodeHeartbeats = new ConcurrentHashMap<>();
  private final Map<String, LeaseState> leases = new ConcurrentHashMap<>();
  private final Map<String, Integer> rollingIndices = new ConcurrentHashMap<>();
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
      Files.createDirectories(assignmentsDir());
      Files.createDirectories(nodesDir());
      Files.createDirectories(rollingDir());
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
    clearRollingIndex(name);
    deleteQuietly(effectiveReplicasFile(name));
    effectiveReplicas.remove(name);
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

  // ---- rolling-update bookkeeping ----

  /**
   * The logical instance index currently being replaced by a rolling update, if any -- persisted so
   * a reconciler restart mid-rollout resumes rather than starting a second one.
   */
  public void putRollingIndex(String deploymentName, int instanceIndex) {
    writeAtomically(rollingFile(deploymentName), rollingToYaml(instanceIndex));
    rollingIndices.put(deploymentName, instanceIndex);
  }

  public void clearRollingIndex(String deploymentName) {
    deleteQuietly(rollingFile(deploymentName));
    rollingIndices.remove(deploymentName);
  }

  public Optional<Integer> getRollingIndex(String deploymentName) {
    return Optional.ofNullable(rollingIndices.get(deploymentName));
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
   * GetNodeHeartbeat} through the current leader specifically for this reason (P2-14) -- a
   * follower's own copy here is never anything but empty, not merely stale.
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
   * {@code ApiServer} replicas once they're decoupled from the store's own Raft membership
   * (claudedocs/etcd-store-extraction-design.md's lease-based-election resolution) -- the same
   * shape Kubernetes' own {@code coordination.k8s.io/v1 Lease} serves for {@code
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
        List.copyOf(nodeRegistrations.values()),
        Map.copyOf(rollingIndices),
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
        auditEventsSnapshotOrder());
  }

  /** Oldest-first, matching {@link #auditEvents}' own internal order -- see {@link #snapshot()}. */
  private List<AuditEvent> auditEventsSnapshotOrder() {
    synchronized (auditEventsLock) {
      return List.copyOf(auditEvents);
    }
  }

  /**
   * Replaces every resource this store holds with {@code snapshot}'s contents -- a follower's
   * response to a leader's {@code InstallSnapshot}, used when this replica has fallen too far
   * behind to catch up via ordinary log replay.
   */
  public void restoreFromSnapshot(StateSnapshot snapshot) {
    List.copyOf(deployments.keySet()).forEach(this::removeDeployment);
    List.copyOf(assignments.values())
        .forEach(a -> removeAssignment(a.deploymentName(), a.instanceIndex()));
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

    snapshot.deployments().forEach(this::putDeployment);
    snapshot.assignments().forEach(this::putAssignment);
    snapshot.nodeRegistrations().forEach(this::putNodeRegistration);
    snapshot.rollingIndices().forEach(this::putRollingIndex);
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

  private Path rollingFile(String deploymentName) {
    return rollingDir().resolve(deploymentName + ".yaml");
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

  private Path assignmentFile(String deploymentName, int instanceIndex) {
    return assignmentsDir().resolve(deploymentName).resolve(instanceIndex + ".yaml");
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
        assignmentsDir(),
        "*/*.yaml",
        file -> {
          InstanceAssignment assignment = assignmentFromMap(loadMap(file));
          assignments.put(
              assignmentKey(assignment.deploymentName(), assignment.instanceIndex()), assignment);
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
        "*.yaml",
        file -> {
          String deploymentName = fileNameWithoutYamlSuffix(file);
          Map<?, ?> map = loadMap(file);
          rollingIndices.put(deploymentName, ((Number) map.get("instanceIndex")).intValue());
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
    Object raw = yaml.load(new ByteArrayInputStream(read(file)));
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

  private static String deploymentToYaml(DeploymentSpec spec) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", spec.name());
    Map<String, Object> module = new LinkedHashMap<>();
    module.put("name", spec.moduleId().name());
    module.put("version", spec.moduleId().version().toString());
    root.put("module", module);
    root.put("artifactPath", spec.artifactPath());
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

  private static String rollingToYaml(int instanceIndex) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("instanceIndex", instanceIndex);
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
    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", assignment.moduleId().name());
    moduleId.put("version", assignment.moduleId().version().toString());
    root.put("moduleId", moduleId);
    root.put("artifactPath", assignment.artifactPath());
    return new Yaml().dump(root);
  }

  private static InstanceAssignment assignmentFromMap(Map<?, ?> map) {
    Map<?, ?> moduleIdMap = (Map<?, ?>) map.get("moduleId");
    ModuleId moduleId =
        new ModuleId(
            (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
    Object artifactPath = map.get("artifactPath");
    return new InstanceAssignment(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        (String) map.get("nodeId"),
        moduleId,
        artifactPath == null ? "" : (String) artifactPath);
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
    Object apiAddress = root.get("apiAddress");
    return new NodeRegistration(
        nodeId,
        new NodeCapabilities(supportedTiers, labels),
        apiAddress == null ? Optional.empty() : Optional.of((String) apiAddress));
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
      Map<String, Object> moduleId = new LinkedHashMap<>();
      moduleId.put("name", obs.moduleId().name());
      moduleId.put("version", obs.moduleId().version().toString());
      m.put("moduleId", moduleId);
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
      Map<?, ?> moduleIdMap = (Map<?, ?>) m.get("moduleId");
      ModuleId moduleId =
          new ModuleId(
              (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
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
      Object tenantScope = m.get("tenantScope");
      permissions.add(
          new Permission(
              resource,
              verb,
              tenantScope == null ? Optional.empty() : Optional.of((String) tenantScope)));
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
    Object causeSummary = root.get("causeSummary");
    return new InstanceEvent(
        (String) root.get("id"),
        (String) root.get("deploymentName"),
        ((Number) root.get("instanceIndex")).intValue(),
        InstanceEventKind.valueOf((String) root.get("kind")),
        (String) root.get("message"),
        causeSummary == null ? Optional.empty() : Optional.of((String) causeSummary),
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
    Object tenantId = root.get("tenantId");
    Object targetId = root.get("targetId");
    return new AuditEvent(
        (String) root.get("id"),
        (String) root.get("principal"),
        groups,
        (String) root.get("resourceKind"),
        (String) root.get("verb"),
        tenantId == null ? Optional.empty() : Optional.of((String) tenantId),
        targetId == null ? Optional.empty() : Optional.of((String) targetId),
        (Boolean) root.get("allowed"),
        ((Number) root.get("occurredAtEpochMilli")).longValue());
  }
}
