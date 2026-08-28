package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 *
 * <p><b>Node-death eviction</b>: unlike {@link DeploymentReconciler}/{@link ReplicaCountReconciler}
 * (dedicated eviction pass) and {@link DaemonSetReconciler} (eligibility exclusion plus its own
 * eviction pass), the {@code OrderedReady} scan's own {@link #isReady} check never inspected the
 * assigned node's own heartbeat freshness -- only whether the *last* heartbeat that node ever sent
 * happened to report this index ready, which stays true forever once a node goes dark and simply
 * stops heartbeating. {@link #nodeIsGenuinelyGone}, checked once per scanned index alongside {@link
 * #isReady}, closes that gap the same stateless way {@link DaemonSetReconciler#hasGoneDark}/{@code
 * isMerelyDarkWithinGracePeriod} already do -- measured directly off {@link
 * ObservedHeartbeat#receivedAt()} against {@code nodeDarkTimeout + placementGracePeriod}, no
 * persisted per-index timer needed. Only the {@link StatefulSetAssignment} is removed, never the
 * sticky {@link StateStore#getStatefulSetIndexNode} binding -- the very same "wait a beat, one
 * destructive step per tick" posture scale-down already takes, so the next tick's scan sees this
 * index as missing and lets {@link #placeIndex} either land it back on the same node once it
 * recovers, or leave it unplaced-and-retrying exactly as this class's own sticky-placement javadoc
 * already documents, never silently relocated to a different node.
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
  private final Duration placementGracePeriod;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public StatefulSetReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public StatefulSetReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(
        store,
        scheduler,
        mutations,
        DEFAULT_NODE_DARK_TIMEOUT,
        DEFAULT_NODE_DARK_TIMEOUT,
        Clock.systemUTC());
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public StatefulSetReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      Clock clock) {
    this(
        store,
        scheduler,
        mutations,
        nodeDarkTimeout,
        placementGracePeriod,
        clock,
        ArtifactResolver.localOnly());
  }

  public StatefulSetReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Duration placementGracePeriod,
      Clock clock,
      ArtifactResolver artifactResolver) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.placementGracePeriod = placementGracePeriod;
    this.clock = clock;
    this.artifactResolver = artifactResolver;
  }

  public void reconcileOnce() {
    Set<String> statefulSetNames = new HashSet<>();
    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      statefulSetNames.add(spec.name());
    }

    // A statefulset no longer in desired state: every one of its assignments is stale, and every
    // one of its sticky node bindings is now genuinely, permanently gone -- there is no spec left
    // that could ever place this index again.
    List<StateMutation> staleRemovals = new ArrayList<>();
    for (StatefulSetAssignment assignment : store.listStatefulSetAssignments()) {
      if (!statefulSetNames.contains(assignment.statefulSetName())) {
        staleRemovals.add(
            new StateMutation.RemoveStatefulSetAssignment(
                assignment.statefulSetName(), assignment.instanceIndex()));
        staleRemovals.add(
            new StateMutation.RemoveStatefulSetIndexNode(
                assignment.statefulSetName(), assignment.instanceIndex()));
      }
    }
    mutations.proposeAll(staleRemovals);

    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      try {
        reconcileStatefulSet(spec);
      } catch (RuntimeException e) {
        // One statefulset's failure (e.g. a GimleRaftException from mutations.propose during a
        // store leader-election gap) must never abort the rest of this tick's statefulsets -- the
        // next tick retries this one from the same full snapshot.
        log.warn("reconcile of statefulset {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileStatefulSet(StatefulSetSpec spec) {
    if (scaleDownOneIndexIfNeeded(spec)) {
      return; // one destructive step per tick -- see class javadoc.
    }

    ModuleArtifact artifact;
    try {
      artifact = artifactResolver.resolve(spec.artifactPath(), spec.moduleId(), spec.vessel());
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
    Instant now = clock.instant();
    for (int index = 0; index < spec.replicas(); index++) {
      int currentIndex = index;
      Optional<StatefulSetAssignment> assignment =
          existing.stream().filter(a -> a.instanceIndex() == currentIndex).findFirst();
      if (assignment.isEmpty()) {
        placeIndex(spec, descriptor, index);
        return;
      }
      if (nodeIsGenuinelyGone(assignment.get().nodeId(), now)) {
        log.warn(
            "statefulset {} index {} on node {} is no longer confirmed by a heartbeat; releasing"
                + " its assignment for re-placement (its sticky node binding is preserved)",
            spec.name(),
            index,
            assignment.get().nodeId());
        mutations.propose(new StateMutation.RemoveStatefulSetAssignment(spec.name(), index));
        return; // one destructive step per tick -- see class javadoc.
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
    List<StateMutation> removal = new ArrayList<>();
    removal.add(
        new StateMutation.RemoveStatefulSetAssignment(spec.name(), assignment.instanceIndex()));
    removal.add(
        new StateMutation.RemoveStatefulSetIndexNode(spec.name(), assignment.instanceIndex()));
    if (store
        .getRollingStatefulSetIndex(spec.name())
        .equals(Optional.of(assignment.instanceIndex()))) {
      removal.add(new StateMutation.ClearRollingStatefulSetIndex(spec.name()));
    }
    mutations.proposeAll(removal);
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
      // The sticky binding is written unconditionally, even when nodeId already equals
      // stickyNodeId -- a first-ever placement (stickyNodeId empty) is exactly when it needs
      // recording for the first time, and re-writing an unchanged value on every later tick is
      // harmless. One batch: the assignment and its binding belong together.
      mutations.proposeAll(
          List.of(
              new StateMutation.PutStatefulSetAssignment(
                  new StatefulSetAssignment(
                      spec.name(), index, nodeId, spec.moduleId(), spec.artifactPath())),
              new StateMutation.PutStatefulSetIndexNode(spec.name(), index, nodeId)));
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
              mutations.proposeAll(
                  List.of(
                      new StateMutation.RemoveStatefulSetAssignment(
                          spec.name(), mismatched.instanceIndex()),
                      new StateMutation.PutRollingStatefulSetIndex(
                          spec.name(), mismatched.instanceIndex())));
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
   * StatefulSetAssignment}s.
   */
  private List<NodeCandidate> buildCandidates(String statefulSetName) {
    Set<String> nodesAlreadyRunningThisStatefulSet = new HashSet<>();
    for (StatefulSetAssignment assignment : store.listStatefulSetAssignments()) {
      if (assignment.statefulSetName().equals(statefulSetName)) {
        nodesAlreadyRunningThisStatefulSet.add(assignment.nodeId());
      }
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
              store.getNodeTaints(registration.nodeId()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }

  /**
   * True once {@code nodeId} is genuinely gone, not merely transiently dark -- either it has never
   * heartbeated at all (the same "nothing plausible left to wait for" reasoning {@link
   * DaemonSetReconciler#isMerelyDarkWithinGracePeriod}'s own javadoc gives for treating a missing
   * heartbeat as immediate), or its last heartbeat is older than {@code nodeDarkTimeout +
   * placementGracePeriod}. Measured directly off the heartbeat's own {@code receivedAt()} rather
   * than a persisted per-index timer -- the same stateless approach {@link
   * DaemonSetReconciler#hasGoneDark} already takes, since "how long has this been true" is already
   * exactly what the heartbeat's own timestamp answers.
   */
  private boolean nodeIsGenuinelyGone(String nodeId, Instant now) {
    Optional<ObservedHeartbeat> heartbeat = store.getNodeHeartbeat(nodeId);
    if (heartbeat.isEmpty()) {
      return true;
    }
    return Duration.between(heartbeat.get().receivedAt(), now)
            .compareTo(nodeDarkTimeout.plus(placementGracePeriod))
        > 0;
  }
}
