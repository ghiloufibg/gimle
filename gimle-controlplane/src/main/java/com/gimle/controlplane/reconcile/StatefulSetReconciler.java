package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import com.gimle.module.artifact.ModuleArtifactReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures a {@link StatefulSetAssignment} exists for every index {@code 0..replicas-1} of every
 * {@link StatefulSetSpec} -- the only workload kind requiring a genuinely new worker/agent
 * capability ({@code VolumeManager}/{@code ModuleContext.dataDirectory()}) rather than reusing
 * existing machinery unchanged.
 *
 * <p>Two properties set this apart from {@link DeploymentReconciler}, both because a StatefulSet
 * index may own a local-disk volume that cannot move: <b>{@code OrderedReady}</b> (Kubernetes
 * StatefulSet's own default, not an alternative invented here) -- index {@code i+1} is never placed
 * until index {@code i} reports ready, enforced simply by scanning {@code 0..replicas-1} in order
 * and stopping at the first index that's either missing (place it, then stop for this tick) or
 * present-but-not-yet-ready (stop without placing anything) -- and <b>sticky placement</b>: once an
 * index is first placed on a node, {@link StateStore#getStatefulSetIndexNode} supplies that same
 * node back to {@link Scheduler#place} on every later placement attempt for that index, via {@code
 * place}'s {@code stickyNodeId} parameter -- a rolling update or a temporarily-dark node never
 * causes an index's data to relocate; it either lands back on the same node or stays unplaced.
 *
 * <p>Rolling updates reuse the same {@code remove-mismatched-then-let-ordinary-placement-repick}
 * shape {@link DeploymentReconciler#handleRollingUpdate} established, persisted via {@link
 * StateStore#putRollingStatefulSetIndex}/{@code getRollingStatefulSetIndex}/{@code
 * clearRollingStatefulSetIndex} -- a separate marker from {@link DeploymentReconciler}'s own
 * in-flight index set, since the two resource kinds never share a namespace. Deliberately reuses no
 * persisted state beyond that: the ordered scan itself (not a second "am I mid-rollout" check) is
 * what keeps a rolling-update removal and an ordinary scale-up from ever racing past each other,
 * since a removed index is simply "missing" to the very same scan either way.
 *
 * <p>Scale-down removes at most one index -- the highest one at or beyond {@code replicas} -- per
 * tick, then returns without attempting any other work that same tick: the same "wait a beat
 * between destructive steps" caution {@code OrderedReady} already applies to placement, extended to
 * teardown. {@link StateStore#removeStatefulSetIndexNode} (permanent) is called only here and on
 * spec deletion below -- never by an ordinary rolling-update or scale-up-triggered removal, which
 * must preserve the sticky binding so the index can find its way back to the same node.
 */
public final class StatefulSetReconciler {

  private static final Logger log = LoggerFactory.getLogger(StatefulSetReconciler.class);

  /** Matches {@link DeploymentReconciler#DEFAULT_NODE_DARK_TIMEOUT} exactly -- see its own note. */
  public static final Duration DEFAULT_NODE_DARK_TIMEOUT =
      DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT;

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;
  private final Duration nodeDarkTimeout;
  private final Clock clock;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public StatefulSetReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public StatefulSetReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(store, scheduler, mutations, DEFAULT_NODE_DARK_TIMEOUT, Clock.systemUTC());
  }

  public StatefulSetReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Clock clock) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.clock = clock;
  }

  public void reconcileOnce() {
    Set<String> statefulSetNames = new HashSet<>();
    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      statefulSetNames.add(spec.name());
    }

    // A statefulset no longer in desired state: every one of its assignments is stale, and every
    // one of its sticky node bindings is now genuinely, permanently gone -- there is no spec left
    // that could ever place this index again.
    for (StatefulSetAssignment assignment : store.listStatefulSetAssignments()) {
      if (!statefulSetNames.contains(assignment.statefulSetName())) {
        mutations.propose(
            new StateMutation.RemoveStatefulSetAssignment(
                assignment.statefulSetName(), assignment.instanceIndex()));
        mutations.propose(
            new StateMutation.RemoveStatefulSetIndexNode(
                assignment.statefulSetName(), assignment.instanceIndex()));
      }
    }

    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      reconcileStatefulSet(spec);
    }
  }

  private void reconcileStatefulSet(StatefulSetSpec spec) {
    if (scaleDownOneIndexIfNeeded(spec)) {
      return; // one destructive step per tick -- see class javadoc.
    }

    ModuleArtifact artifact;
    try {
      artifact = ModuleArtifactReader.read(Path.of(spec.artifactPath()));
    } catch (RuntimeException e) {
      log.warn(
          "statefulset {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      return;
    }
    // Matches DeploymentReconciler's own artifact-hash check exactly: an artifact silently
    // swapped out from under a running statefulset name is refused, not silently followed.
    if (spec.artifactSha256().isPresent()
        && !spec.artifactSha256().get().equals(artifact.sha256())) {
      log.warn(
          "statefulset {} artifact at {} no longer matches the hash recorded at admission"
              + " (expected {}, found {}) -- refusing to place new instances until the spec is"
              + " resubmitted",
          spec.name(),
          spec.artifactPath(),
          spec.artifactSha256().get(),
          artifact.sha256());
      return;
    }
    ModuleDescriptor descriptor = artifact.descriptor();

    handleRollingUpdate(spec, descriptor);

    // Re-read: handleRollingUpdate above may have just removed an entry.
    List<StatefulSetAssignment> existing = store.listStatefulSetAssignmentsFor(spec.name());

    // OrderedReady (Kubernetes StatefulSet's own default): scan indices low to high, stopping at
    // the first one that isn't both present and ready. Placing that one missing index (if any) and
    // returning is what keeps index i+1 from ever being attempted before index i is ready --
    // no separate "am I mid-rollout" bookkeeping needed beyond this scan itself.
    for (int index = 0; index < spec.replicas(); index++) {
      int currentIndex = index;
      Optional<StatefulSetAssignment> assignment =
          existing.stream().filter(a -> a.instanceIndex() == currentIndex).findFirst();
      if (assignment.isEmpty()) {
        placeIndex(spec, descriptor, index);
        return;
      }
      if (!isReady(assignment.get())) {
        return;
      }
    }
  }

  /**
   * Removes the highest index at or beyond {@code spec.replicas()}, if any, along with its
   * permanent sticky binding and (if it happened to be the one mid-rollout) the now-meaningless
   * rolling marker. Returns {@code true} if it did so -- the caller stops for this tick either way.
   */
  private boolean scaleDownOneIndexIfNeeded(StatefulSetSpec spec) {
    Optional<StatefulSetAssignment> toRemove =
        store.listStatefulSetAssignmentsFor(spec.name()).stream()
            .filter(a -> a.instanceIndex() >= spec.replicas())
            .max(Comparator.comparingInt(StatefulSetAssignment::instanceIndex));
    if (toRemove.isEmpty()) {
      return false;
    }
    StatefulSetAssignment assignment = toRemove.get();
    mutations.propose(
        new StateMutation.RemoveStatefulSetAssignment(spec.name(), assignment.instanceIndex()));
    mutations.propose(
        new StateMutation.RemoveStatefulSetIndexNode(spec.name(), assignment.instanceIndex()));
    if (store
        .getRollingStatefulSetIndex(spec.name())
        .equals(Optional.of(assignment.instanceIndex()))) {
      mutations.propose(new StateMutation.ClearRollingStatefulSetIndex(spec.name()));
    }
    return true;
  }

  private void placeIndex(StatefulSetSpec spec, ModuleDescriptor descriptor, int index) {
    Optional<String> stickyNodeId = store.getStatefulSetIndexNode(spec.name(), index);
    try {
      List<NodeCandidate> candidates = buildCandidates(spec.name());
      String nodeId =
          scheduler.place(
              spec.name(),
              index,
              descriptor.isolationTier(),
              descriptor.resourceRequest(),
              spec.placement().antiAffinityAcrossNodes(),
              spec.tenantId(),
              spec.placement().requiredNodeLabels().orElse(Set.of()),
              stickyNodeId,
              candidates);
      mutations.propose(
          new StateMutation.PutStatefulSetAssignment(
              new StatefulSetAssignment(
                  spec.name(), index, nodeId, spec.moduleId(), spec.artifactPath())));
      // Written unconditionally, even when nodeId already equals stickyNodeId -- a first-ever
      // placement (stickyNodeId empty) is exactly when this needs to be recorded for the first
      // time, and re-writing an unchanged value on every later tick is harmless.
      mutations.propose(new StateMutation.PutStatefulSetIndexNode(spec.name(), index, nodeId));
    } catch (GimleSchedulingException e) {
      // Left unplaced; the next tick retries from the same full snapshot -- matches every other
      // reconciler's "a missed placement this tick is indistinguishable from a retried one"
      // posture. For a sticky index specifically, this is also the documented "data does not
      // survive node loss" outcome: it stays unplaced-and-retrying, never
      // silently relocated to a different node.
      log.warn("could not place {} instance {}: {}", spec.name(), index, e.getMessage());
    }
  }

  /**
   * Direct structural counterpart to {@link DeploymentReconciler#handleRollingUpdate}: if a rollout
   * is already in flight, checks whether the replacement at that index has landed and reported
   * ready, clearing {@code rollingStatefulSetIndex} once it has; otherwise looks for a new version
   * mismatch to start, picking the lowest such index and removing its stale assignment (its sticky
   * node binding survives this removal untouched) so {@link #reconcileStatefulSet}'s own
   * OrderedReady scan re-places it with the current spec's {@code moduleId} on that same node.
   */
  private void handleRollingUpdate(StatefulSetSpec spec, ModuleDescriptor descriptor) {
    Optional<Integer> rollingIndex = store.getRollingStatefulSetIndex(spec.name());
    if (rollingIndex.isPresent()) {
      int index = rollingIndex.get();
      Optional<StatefulSetAssignment> current =
          store.listStatefulSetAssignmentsFor(spec.name()).stream()
              .filter(a -> a.instanceIndex() == index)
              .findFirst();
      if (current.isPresent() && isReady(current.get())) {
        mutations.propose(new StateMutation.ClearRollingStatefulSetIndex(spec.name()));
      }
      return;
    }

    store.listStatefulSetAssignmentsFor(spec.name()).stream()
        .filter(assignment -> !assignment.moduleId().equals(spec.moduleId()))
        .min(Comparator.comparingInt(StatefulSetAssignment::instanceIndex))
        .ifPresent(
            mismatched -> {
              mutations.propose(
                  new StateMutation.RemoveStatefulSetAssignment(
                      spec.name(), mismatched.instanceIndex()));
              mutations.propose(
                  new StateMutation.PutRollingStatefulSetIndex(
                      spec.name(), mismatched.instanceIndex()));
              log.info(
                  "statefulset {} index {} is on an old module version; rolling it forward",
                  spec.name(),
                  mismatched.instanceIndex());
            });
  }

  /**
   * True only once the node's own heartbeat reports THIS index as both {@code ready} and actually
   * running {@code assignment}'s own {@code moduleId} -- mirrors {@link
   * DeploymentReconciler#isReady} exactly.
   */
  private boolean isReady(StatefulSetAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .anyMatch(
            obs ->
                obs.deploymentName().equals(assignment.statefulSetName())
                    && obs.instanceIndex() == assignment.instanceIndex()
                    && obs.moduleId().equals(assignment.moduleId())
                    && obs.ready());
  }

  /**
   * Mirrors {@link DeploymentReconciler#buildCandidates} exactly, built from {@link
   * StatefulSetAssignment}s. Folds in {@link InstanceAssignment} and {@link DaemonSetAssignment}
   * tenant occupancy too, the same one-directional fold {@code DaemonSetReconciler.buildCandidates}
   * already documents for Deployment -- {@code DeploymentReconciler} is deliberately left
   * untouched, so this is not symmetric.
   */
  private List<NodeCandidate> buildCandidates(String statefulSetName) {
    Set<String> nodesAlreadyRunningThisStatefulSet = new HashSet<>();
    Map<String, Set<String>> tenantsByNode = new HashMap<>();
    for (StatefulSetAssignment assignment : store.listStatefulSetAssignments()) {
      if (assignment.statefulSetName().equals(statefulSetName)) {
        nodesAlreadyRunningThisStatefulSet.add(assignment.nodeId());
      }
      store
          .getStatefulSetSpec(assignment.statefulSetName())
          .flatMap(StatefulSetSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }
    for (InstanceAssignment assignment : store.listAssignments()) {
      store
          .getDeployment(assignment.deploymentName())
          .flatMap(DeploymentSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }
    for (DaemonSetAssignment assignment : store.listDaemonSetAssignments()) {
      store
          .getDaemonSetSpec(assignment.daemonSetName())
          .flatMap(DaemonSetSpec::tenantId)
          .ifPresent(
              tenantId ->
                  tenantsByNode
                      .computeIfAbsent(assignment.nodeId(), key -> new HashSet<>())
                      .add(tenantId));
    }

    Instant now = clock.instant();
    List<NodeCandidate> candidates = new ArrayList<>();
    for (NodeRegistration registration : store.listNodeRegistrations()) {
      Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(registration.nodeId());
      if (heartbeat.isEmpty()) {
        continue; // no capacity report yet; not a placement candidate until it heartbeats
      }
      if (hasGoneDark(heartbeat.get(), now)) {
        continue; // see DeploymentReconciler.buildCandidates's own identical comment
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisStatefulSet.contains(registration.nodeId()),
              tenantsByNode.getOrDefault(registration.nodeId(), Set.of()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }
}
