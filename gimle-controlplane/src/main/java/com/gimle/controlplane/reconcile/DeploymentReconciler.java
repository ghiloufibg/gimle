package com.gimle.controlplane.reconcile;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StoreReader;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures an {@link InstanceAssignment} exists for every index {@code 0..replicas-1} of every
 * {@link DeploymentSpec}. Level-triggered: every tick re-derives the full set of assignments a
 * from-scratch run would produce from the current snapshot, rather than reacting to what changed
 * since last tick -- deleting a deployment, scaling it, or a fresh empty store all converge through
 * the exact same code path.
 *
 * <p>Rolling updates piggyback on this same convergence loop rather than a parallel mechanism: when
 * a mismatched-{@code moduleId} assignment is found, {@link #handleRollingUpdate} simply removes it
 * (persisting which index is mid-transition via {@link StateStore#addRollingIndex}) and lets the
 * ordinary missing-index placement logic below re-place it with the current spec's {@code moduleId}
 * -- exactly the same code path a fresh scale-up already uses, so a rolling update needs no
 * dedicated placement logic of its own. Up to {@link
 * com.gimle.mimir.manifest.DisruptionBudget#maxUnavailable} indices migrate concurrently (default
 * {@code 1}, matching this reconciler's behavior before {@code disruption:} existed): a freed slot
 * is topped up with a new migration the moment budget allows, even within the same tick a prior one
 * clears, rather than waiting for the whole in-flight batch to drain. This state survives a
 * reconciler restart because it's read back from the persisted rolling-index set rather than kept
 * only in memory.
 *
 * <p>{@link #handleSurge} is the mirror-image budget -- see its own javadoc for the synthetic-index
 * promotion mechanism. The two budgets run as independent passes over the same mismatched-index
 * list each tick, each excluding indices the other has already claimed.
 */
public final class DeploymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(DeploymentReconciler.class);

  /**
   * How stale a node's last heartbeat may be before this reconciler stops treating it as somewhere
   * an instance could be placed. Matches {@code ReplicaCountReconciler}'s own node-dark timeout --
   * the two have to agree, or one would release an assignment the other immediately re-places on
   * the same silent node.
   */
  public static final Duration DEFAULT_NODE_DARK_TIMEOUT = Duration.ofSeconds(15);

  private final StoreReader store;
  private final Scheduler scheduler;
  private final MutationSink mutations;
  private final Duration nodeDarkTimeout;
  private final Clock clock;
  private final ArtifactResolver artifactResolver;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public DeploymentReconciler(StateStore store, Scheduler scheduler) {
    this(store, scheduler, mutation -> mutation.applyTo(store));
  }

  public DeploymentReconciler(StoreReader store, Scheduler scheduler, MutationSink mutations) {
    this(store, scheduler, mutations, DEFAULT_NODE_DARK_TIMEOUT, Clock.systemUTC());
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public DeploymentReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Clock clock) {
    this(store, scheduler, mutations, nodeDarkTimeout, clock, ArtifactResolver.localOnly());
  }

  public DeploymentReconciler(
      StoreReader store,
      Scheduler scheduler,
      MutationSink mutations,
      Duration nodeDarkTimeout,
      Clock clock,
      ArtifactResolver artifactResolver) {
    this.store = store;
    this.scheduler = scheduler;
    this.mutations = mutations;
    this.nodeDarkTimeout = nodeDarkTimeout;
    this.clock = clock;
    this.artifactResolver = artifactResolver;
  }

  public void reconcileOnce() {
    Set<String> deploymentNames = new HashSet<>();
    for (DeploymentSpec spec : store.listDeployments()) {
      deploymentNames.add(spec.name());
    }

    // A deployment no longer in desired state: every one of its assignments is stale. One batch
    // for the whole sweep -- the removals are independent of each other and nothing below reads
    // them back before the per-deployment passes run.
    List<StateMutation> staleAssignmentRemovals = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      if (!deploymentNames.contains(assignment.deploymentName())) {
        staleAssignmentRemovals.add(
            new StateMutation.RemoveAssignment(
                assignment.deploymentName(), assignment.instanceIndex()));
      }
    }
    mutations.proposeAll(staleAssignmentRemovals);

    for (DeploymentSpec spec : store.listDeployments()) {
      try {
        reconcileDeployment(spec);
      } catch (RuntimeException e) {
        // One deployment's failure (e.g. a GimleRaftException from mutations.propose during a
        // store leader-election gap) must never abort the rest of this tick's deployments -- the
        // next tick retries this one from the same full snapshot, the same level-triggered posture
        // placeInstances' own per-index catch below already relies on.
        log.warn("reconcile of deployment {} failed: {}", spec.name(), e.getMessage(), e);
      }
    }
  }

  private void reconcileDeployment(DeploymentSpec spec) {
    // The autoscaler's effective count stands in for the user-submitted replicas whenever a
    // policy is present; absent a policy (or absent any computed value yet), the submitted count
    // is exactly what's used, unchanged from before autoscaling existed.
    int replicas = store.getEffectiveReplicas(spec.name()).orElse(spec.replicas());

    reclaimStaleAssignments(spec, replicas);

    handleRollingUpdate(spec, replicas);
    handleSurge(spec, replicas);

    // Re-read: scale-down and/or the rolling-update/surge steps above may have just removed or
    // added an entry.
    Set<Integer> existingIndices = new HashSet<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.name())) {
      existingIndices.add(assignment.instanceIndex());
    }

    List<Integer> toPlace = indicesNeedingPlacement(spec, replicas, existingIndices);
    if (toPlace.isEmpty()) {
      return;
    }

    Optional<ModuleDescriptor> descriptor = validateArtifact(spec, toPlace);
    if (descriptor.isEmpty()) {
      return;
    }

    placeInstances(spec, toPlace, descriptor.get());
  }

  /**
   * Scale-down: an assigned index beyond {@code replicas} is removed immediately (a desired-state
   * edit only) -- the agent's own stop()/StopModule drain timing owns teardown, not this
   * reconciler. A live surge assignment is excluded: it's also {@code >= replicas} by construction,
   * but tearing it down here would defeat the whole point of maxSurge (see {@link #handleSurge}).
   * Promotion removes it explicitly, in the same tick, once retargeted onto its target index --
   * this exclusion only ever matters for an abandoned surge (replicas shrank below its target
   * mid-flight): its tracking entry clears without its assignment being removed, so this same loop
   * reclaims that one on a later tick once it's no longer excluded.
   */
  private void reclaimStaleAssignments(DeploymentSpec spec, int replicas) {
    Map<Integer, Integer> surgeIndicesBeforeThisTick = store.getSurgeIndices(spec.name());
    List<StateMutation> removals = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.name())) {
      if (assignment.instanceIndex() >= replicas
          && !surgeIndicesBeforeThisTick.containsKey(assignment.instanceIndex())) {
        removals.add(new StateMutation.RemoveAssignment(spec.name(), assignment.instanceIndex()));
      }
    }
    mutations.proposeAll(removals);
  }

  /**
   * Reads {@code spec}'s artifact and verifies it against the hash recorded at admission, returning
   * the descriptor once both the read and the hash check succeed. A spec admitted with a recorded
   * hash (Optional.empty() means admitted before this field existed, or the artifact was unreadable
   * at admission time -- both skip the check) must still match the bytes on disk at every tick, not
   * just at admission -- an artifact silently swapped out from under a running deployment name is
   * exactly what this guards against. Either failure is already logged by the time this returns
   * Optional.empty() -- the caller's only job from there is to skip placement this tick.
   *
   * <p>{@code blockedIndices} is exactly {@link #reconcileDeployment}'s own {@code toPlace} for
   * this tick -- every index left unplaced because of this failure gets a durable {@code
   * TRANSITION_FAILED} event via {@link #recordArtifactFailure}, not just the platform log line
   * above. Without this, an unreadable/tampered artifact left a deployment silently stuck at {@code
   * UNPLACED} forever with nothing in {@code gimle events} ever explaining why -- the only trace
   * was this WARN, re-logged every tick, in the control plane's own platform log file.
   */
  private Optional<ModuleDescriptor> validateArtifact(
      DeploymentSpec spec, List<Integer> blockedIndices) {
    ModuleArtifact artifact;
    try {
      artifact = artifactResolver.resolve(spec.artifactPath(), spec.moduleId(), spec.vessel());
    } catch (RuntimeException e) {
      log.warn(
          "deployment {} references an unreadable artifact {}: {}",
          spec.name(),
          spec.artifactPath(),
          e.getMessage());
      recordArtifactFailure(
          spec,
          blockedIndices,
          "artifact unreadable: " + spec.artifactPath(),
          String.valueOf(e.getMessage()));
      return Optional.empty();
    }
    if (spec.artifactSha256().isPresent()
        && !spec.artifactSha256().get().equals(artifact.sha256())) {
      log.warn(
          "deployment {} artifact at {} no longer matches the hash recorded at admission"
              + " (expected {}, found {}) -- refusing to place new instances until the spec is"
              + " resubmitted",
          spec.name(),
          spec.artifactPath(),
          spec.artifactSha256().get(),
          artifact.sha256());
      recordArtifactFailure(
          spec,
          blockedIndices,
          "artifact hash no longer matches the one recorded at admission: " + spec.artifactPath(),
          "expected " + spec.artifactSha256().get() + ", found " + artifact.sha256());
      return Optional.empty();
    }
    return Optional.of(artifact.descriptor());
  }

  /**
   * One {@code TRANSITION_FAILED} event per blocked index, deduplicated against each index's own
   * most recent event so a persistently-unreadable artifact records this once, not every 2s the
   * reconcile loop retries -- {@link StateStore}'s own per-instance retention cap bounds the
   * timeline, but there is no reason to spend any of it on the exact same message repeated forever.
   */
  private void recordArtifactFailure(
      DeploymentSpec spec, List<Integer> blockedIndices, String message, String cause) {
    for (int index : blockedIndices) {
      List<InstanceEvent> existing = store.listInstanceEvents(spec.name(), index);
      if (!existing.isEmpty()
          && existing.get(0).kind() == InstanceEventKind.TRANSITION_FAILED
          && existing.get(0).message().equals(message)) {
        continue;
      }
      mutations.propose(
          new StateMutation.AppendInstanceEvent(
              new InstanceEvent(
                  UUID.randomUUID().toString(),
                  spec.name(),
                  index,
                  InstanceEventKind.TRANSITION_FAILED,
                  message,
                  Optional.of(cause),
                  clock.millis())));
    }
  }

  private void placeInstances(
      DeploymentSpec spec, List<Integer> toPlace, ModuleDescriptor descriptor) {
    // Nodes this very loop has already chosen for this deployment, this tick -- buildCandidates
    // otherwise only sees store.listAssignments(), which mutations.propose below does not affect
    // (proposals commit after this whole reconciliation pass, not per-call), so a fresh multi-
    // replica placement's own later indices could never see its own earlier ones in the same tick.
    // Without this, antiAffinity silently failed to spread a brand-new deployment's replicas at
    // all: every index scored the same "no existing replicas anywhere" snapshot and the scheduler's
    // deterministic tie-break picked the identical best-scoring node for every one of them.
    Set<String> placedThisTick = new HashSet<>();
    // One batch for the whole burst: a fresh multi-replica placement pays one consensus round and
    // one WAL fsync instead of one per index. An index that failed to schedule simply isn't in
    // the batch -- the next tick retries it, unchanged.
    List<StateMutation> placements = new ArrayList<>();
    for (int index : toPlace) {
      try {
        List<NodeCandidate> candidates = buildCandidates(spec.name(), placedThisTick);
        String nodeId =
            scheduler.place(
                spec.name(),
                index,
                descriptor.isolationTier(),
                descriptor.resourceRequest(),
                spec.placement().antiAffinityAcrossNodes(),
                spec.tenantId(),
                spec.placement().requiredNodeLabels().orElse(Set.of()),
                candidates);
        placedThisTick.add(nodeId);
        placements.add(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    spec.name(), index, nodeId, spec.moduleId(), spec.artifactPath())));
      } catch (GimleSchedulingException e) {
        // Left unplaced; the next tick retries from the same full snapshot, no special-cased
        // retry bookkeeping needed -- this is what "level-triggered, converge from any snapshot"
        // buys: a missed placement this tick is indistinguishable from one that failed and is
        // being retried.
        log.warn("could not place {} instance {}: {}", spec.name(), index, e.getMessage());
      }
    }
    mutations.proposeAll(placements);
  }

  /**
   * Tops up the set of in-flight migrations to the deployment's effective {@link
   * com.gimle.mimir.manifest.DisruptionBudget#maxUnavailable} every tick: first checks every
   * already-in-flight index for readiness, clearing it once its replacement has landed and reported
   * ready for whatever {@code moduleId} it was actually placed at (a stalled migration blocks only
   * itself, never the others); then, if budget remains, starts new migrations for the
   * lowest-indexed mismatches not already in flight -- removing each one's stale assignment so the
   * caller's ordinary missing-index placement logic re-places it with the current spec's {@code
   * moduleId}, the exact same placement path a fresh scale-up already uses. A freed slot is topped
   * up in the very same tick it frees, not the next one: {@code maxUnavailable} keeps that many
   * migrations continuously in flight rather than draining a whole batch before starting the next.
   *
   * <p>Deliberately does <em>not</em> also require {@code current.get().moduleId()} to equal {@code
   * spec.moduleId()} when checking readiness -- a real-cluster finding {@code RedeployStabilityIT}
   * regression-tests: {@code spec} is this method's caller's live, current snapshot, which can
   * already have raced ahead to a newer version by the time this tick runs (an operator or pipeline
   * submitting a second version before an index's first migration was confirmed ready). Gating the
   * clear on matching that possibly-newer spec would deadlock the index's rollout permanently once
   * the equality could no longer pass. {@link #isReady} already independently confirms the live
   * heartbeat's own observation matches the assignment actually placed (not the spec's), which is
   * the only check "did this specific migration step land" actually needs -- clearing frees the
   * slot for the mismatch scan below to immediately pick up any newer spec on its own, chaining
   * rollouts instead of deadlocking on one.
   */
  private void handleRollingUpdate(DeploymentSpec spec, int replicas) {
    int maxUnavailable = spec.effectiveDisruptionBudget().maxUnavailable();
    Set<Integer> inFlight = new HashSet<>(store.getRollingIndices(spec.name()));
    // Accumulated for one flush at method end: nothing in this method reads its own proposals
    // back from the store (the in-flight set is tracked locally), and handleSurge's own reads run
    // only after this method has returned -- so one batch per tick replaces up to
    // 2*maxUnavailable-plus-clears individual consensus rounds with no visibility change.
    List<StateMutation> changes = new ArrayList<>();

    for (int index : Set.copyOf(inFlight)) {
      Optional<InstanceAssignment> current =
          store.listAssignmentsFor(spec.name()).stream()
              .filter(a -> a.instanceIndex() == index)
              .findFirst();
      if (current.isPresent() && isReady(current.get())) {
        changes.add(new StateMutation.RemoveRollingIndex(spec.name(), index));
        inFlight.remove(index);
      } else if (current.isEmpty() && index >= replicas) {
        // The scale-down race: replicas shrank below this index while its old assignment was
        // already removed for migration (awaiting a replacement that will now never come, since
        // the missing-index placement loop below only ever fills 0..replicas-1). Without this,
        // the index would stay "in flight" forever -- current.isEmpty() alone isn't sufficient to
        // clear it, since that's also the ordinary "removed old assignment, replacement not
        // placed yet" state every in-progress migration passes through on its way to becoming
        // ready.
        changes.add(new StateMutation.RemoveRollingIndex(spec.name(), index));
        inFlight.remove(index);
      } else if (store
          .getReconcilerInstanceState(spec.name(), index)
          .map(ReconcilerInstanceState::permanentlyFailed)
          .orElse(false)) {
        // HealthReconciler exhausted this index's restart budget and will never touch it again
        // (its own permanentlyFailed early-return). Without this, the in-flight marker above never
        // clears on its own -- current.isPresent()-but-never-ready and current.isEmpty()-but-
        // index<replicas both fall through every other branch here forever, permanently consuming
        // this deployment's whole maxUnavailable budget and blocking every future rollout AND
        // rollback for it, no matter how many ticks pass. Clearing it doesn't touch the
        // instance itself (still whatever HealthReconciler left it as) -- it just stops one broken
        // index from wedging the entire deployment.
        mutations.propose(new StateMutation.RemoveRollingIndex(spec.name(), index));
        inFlight.remove(index);
        log.warn(
            "deployment {} instance {} gave up retrying after exhausting its restart budget;"
                + " freeing the rolling-update budget it was holding",
            spec.name(),
            index);
      }
      // Otherwise: still waiting for the replacement to land, or already removed and awaiting
      // re-placement below -- either way, this index keeps consuming budget until it clears.
    }

    if (inFlight.size() >= maxUnavailable) {
      mutations.proposeAll(changes);
      return;
    }

    // Also excludes any index #handleSurge has already claimed as a surge target this tick --
    // maxUnavailable and maxSurge are independent budgets, but the same mismatched index must
    // never be migrated by both at once.
    Set<Integer> excluded = new HashSet<>(inFlight);
    excluded.addAll(store.getSurgeIndices(spec.name()).values());
    mismatchedAssignments(spec, excluded).stream()
        .limit(maxUnavailable - inFlight.size())
        .forEach(
            mismatched -> {
              changes.add(
                  new StateMutation.RemoveAssignment(spec.name(), mismatched.instanceIndex()));
              changes.add(
                  new StateMutation.AddRollingIndex(spec.name(), mismatched.instanceIndex()));
              log.info(
                  "deployment {} instance {} is on an old module version; rolling it forward",
                  spec.name(),
                  mismatched.instanceIndex());
            });
    mutations.proposeAll(changes);
  }

  /**
   * Every assignment of {@code spec} whose {@code moduleId} or {@code artifactPath} no longer
   * matches the spec's own, sorted by index, excluding whichever indices {@code excludedIndices}
   * already claims -- the shared "find migration candidates" scan both {@link
   * #handleRollingUpdate}'s {@code maxUnavailable} budget and {@link #handleSurge}'s {@code
   * maxSurge} budget run, differing only in which in-flight/claimed indices each passes in to keep
   * the other budget's own claims out of reach. UNSPECIFIED_MODULE (the three-argument {@link
   * InstanceAssignment} constructor's placeholder) means "don't care which version this is" --
   * treating it as a real mismatch would spuriously trigger a rollout for every assignment that
   * simply never specified one. Comparing {@code artifactPath} alongside {@code moduleId} matters
   * for the same reason the admission layer's own "meaningfully changed" check (which decides
   * whether a re-applied manifest mints a new {@code ControllerRevision}) already treats an
   * artifact-only change as real: a re-applied manifest with the same {@code moduleId} but a
   * patched jar at a new path must actually roll out, not just look like it did in {@code
   * deployment revisions}. {@code artifactSha256} is deliberately not compared here too -- {@link
   * InstanceAssignment} doesn't carry it, and a same-path artifact silently swapped out from under
   * a running instance is {@link #validateArtifact}'s own, separate concern.
   */
  private List<InstanceAssignment> mismatchedAssignments(
      DeploymentSpec spec, Set<Integer> excludedIndices) {
    return store.listAssignmentsFor(spec.name()).stream()
        .filter(assignment -> !assignment.moduleId().equals(InstanceAssignment.UNSPECIFIED_MODULE))
        .filter(assignment -> isStale(assignment, spec))
        .filter(assignment -> !excludedIndices.contains(assignment.instanceIndex()))
        .sorted(Comparator.comparingInt(InstanceAssignment::instanceIndex))
        .toList();
  }

  private static boolean isStale(InstanceAssignment assignment, DeploymentSpec spec) {
    return !assignment.moduleId().equals(spec.moduleId())
        || !assignment.artifactPath().equals(spec.artifactPath());
  }

  /**
   * Tops up the set of in-flight surge slots to the deployment's effective {@link
   * com.gimle.mimir.manifest.DisruptionBudget#maxSurge} every tick, independently of {@link
   * #handleRollingUpdate}'s own {@code maxUnavailable} budget -- the two run as separate passes
   * over the same mismatched-index list, each excluding indices the other has already claimed.
   * Unlike {@code maxUnavailable}, which removes a stale assignment before its replacement lands,
   * surge provisions the replacement first, at a synthetic index {@code >= replicas} the ordinary
   * {@code 0..replicas-1} range never uses, so migrating an index never drops the deployment's
   * running count below {@code replicas} even momentarily. Bookkeeping only: this method never
   * calls {@link Scheduler#place} itself, the same way {@link #handleRollingUpdate} doesn't --
   * {@link #reconcileDeployment}'s own missing-index placement loop, via {@link
   * #indicesNeedingPlacement}, does the actual scheduling for a newly-tracked surge index the same
   * way it already does for an ordinary missing one. Promotion is bookkeeping too, just of a
   * different shape: it copies the surge instance's already-known {@code nodeId} straight onto the
   * target index's new assignment rather than asking the scheduler to pick one, since the whole
   * point is reusing a placement decision already proven healthy, not making a new one.
   */
  private void handleSurge(DeploymentSpec spec, int replicas) {
    int maxSurge = spec.effectiveDisruptionBudget().maxSurge();
    Map<Integer, Integer> inFlight = new HashMap<>(store.getSurgeIndices(spec.name()));
    // Flushed as one batch *before* the mismatch scan below, not at method end: a promotion's
    // PutAssignment is what stops its target index from still looking mismatched, so deferring it
    // past the scan would start a pointless second surge for an index just promoted.
    List<StateMutation> settlements = new ArrayList<>();

    for (Map.Entry<Integer, Integer> entry : Map.copyOf(inFlight).entrySet()) {
      int surgeIndex = entry.getKey();
      int targetIndex = entry.getValue();
      if (targetIndex >= replicas) {
        // replicas shrank below this promotion's target while it was in flight -- abandon rather
        // than promote into an index that no longer exists. The surge assignment itself, now
        // untracked, is reclaimed by reconcileDeployment's own scale-down sweep on a later tick
        // once it's no longer excluded from it.
        settlements.add(new StateMutation.RemoveSurgeIndex(spec.name(), surgeIndex));
        inFlight.remove(surgeIndex);
        continue;
      }
      if (store
          .getReconcilerInstanceState(spec.name(), surgeIndex)
          .map(ReconcilerInstanceState::permanentlyFailed)
          .orElse(false)) {
        // The surge instance itself exhausted its restart budget and HealthReconciler will never
        // touch it again -- same reasoning as handleRollingUpdate's own equivalent check: without
        // this, a broken surge candidate holds its maxSurge slot forever, since it can never become
        // ready and promote, and nothing else here ever reconsiders it.
        mutations.propose(new StateMutation.RemoveSurgeIndex(spec.name(), surgeIndex));
        inFlight.remove(surgeIndex);
        log.warn(
            "deployment {} surge candidate at index {} (target {}) gave up retrying; freeing the"
                + " surge budget it was holding",
            spec.name(),
            surgeIndex,
            targetIndex);
        continue;
      }
      Optional<InstanceAssignment> surgeAssignment =
          store.listAssignmentsFor(spec.name()).stream()
              .filter(a -> a.instanceIndex() == surgeIndex)
              .findFirst();
      if (surgeAssignment.isPresent() && isReady(surgeAssignment.get())) {
        // Promotion: retarget the already-healthy surge instance onto targetIndex in place,
        // rather than tearing the old target down and letting the missing-index loop schedule a
        // fresh instance there. PutAssignment overwrites whatever stale (old-moduleId) assignment
        // targetIndex still has, the same way an ordinary fresh placement would -- but this one
        // carries renamedFromInstanceIndex, which AgentMain#reconcileAssignments recognizes and
        // re-keys its already-running worker instance from, instead of restarting it (see
        // AssignedInstance's own javadoc). The surge index's own now-redundant assignment is
        // removed in the same tick -- no orphaned entry left for a later scale-down sweep to find.
        InstanceAssignment healthy = surgeAssignment.get();
        settlements.add(
            new StateMutation.PutAssignment(
                new InstanceAssignment(
                    spec.name(),
                    targetIndex,
                    healthy.nodeId(),
                    healthy.moduleId(),
                    healthy.artifactPath(),
                    OptionalInt.of(surgeIndex))));
        settlements.add(new StateMutation.RemoveAssignment(spec.name(), surgeIndex));
        settlements.add(new StateMutation.RemoveSurgeIndex(spec.name(), surgeIndex));
        inFlight.remove(surgeIndex);
      }
      // Otherwise: still waiting for the surge instance to report ready.
    }
    mutations.proposeAll(settlements);

    if (inFlight.size() >= maxSurge) {
      return;
    }

    Set<Integer> rollingInFlight = store.getRollingIndices(spec.name());
    Set<Integer> claimedTargets = new HashSet<>(inFlight.values());
    Set<Integer> takenSyntheticIndices = new HashSet<>(inFlight.keySet());
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.name())) {
      takenSyntheticIndices.add(assignment.instanceIndex());
    }

    // Excludes rollingInFlight (handleRollingUpdate's own claimed indices) and claimedTargets
    // (this method's own already-tracked surge targets) -- the same "keep the other budget's
    // claims out of reach" exclusion handleRollingUpdate's own call applies in the other
    // direction.
    Set<Integer> excluded = new HashSet<>(rollingInFlight);
    excluded.addAll(claimedTargets);
    List<StateMutation> newSurges = new ArrayList<>();
    mismatchedAssignments(spec, excluded).stream()
        .limit(maxSurge - inFlight.size())
        .forEach(
            mismatched -> {
              int surgeIndex = nextFreeSurgeIndex(replicas, takenSyntheticIndices);
              takenSyntheticIndices.add(surgeIndex);
              newSurges.add(
                  new StateMutation.AddSurgeIndex(
                      spec.name(), surgeIndex, mismatched.instanceIndex()));
              log.info(
                  "deployment {} instance {} is on an old module version; surging a replacement at"
                      + " index {} ahead of removing it",
                  spec.name(),
                  mismatched.instanceIndex(),
                  surgeIndex);
            });
    mutations.proposeAll(newSurges);
  }

  /**
   * The lowest synthetic index {@code >= replicas} not already claimed by a live assignment or a
   * surge slot started earlier in this same tick.
   */
  private static int nextFreeSurgeIndex(int replicas, Set<Integer> taken) {
    int candidate = replicas;
    while (taken.contains(candidate)) {
      candidate++;
    }
    return candidate;
  }

  /**
   * True only once the node's own heartbeat reports THIS index as both {@code ready} and actually
   * running {@code assignment}'s own {@code moduleId} -- checking {@code ready} alone would false-
   * positive on the exact heartbeat cycle a rollout starts: the previous, still-{@code ready} old
   * instance's last-received observation would otherwise look indistinguishable from the new one
   * already having landed, letting {@link #handleRollingUpdate} clear this index's in-flight marker
   * and move on to the next index before the replacement has even been placed, let alone started.
   */
  private boolean isReady(InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .map(NodeHeartbeat::instances)
        .orElse(List.of())
        .stream()
        .anyMatch(
            obs ->
                obs.deploymentName().equals(assignment.deploymentName())
                    && obs.instanceIndex() == assignment.instanceIndex()
                    && obs.moduleId().equals(assignment.moduleId())
                    && obs.ready());
  }

  private static List<Integer> missingIndices(int replicas, Set<Integer> existingIndices) {
    List<Integer> missing = new ArrayList<>();
    for (int index = 0; index < replicas; index++) {
      if (!existingIndices.contains(index)) {
        missing.add(index);
      }
    }
    return missing;
  }

  /**
   * Every index the missing-index placement loop must fill this tick: the ordinary {@code
   * 0..replicas-1} gaps {@link #missingIndices} already computed, plus any synthetic surge index
   * {@link #handleSurge} tracked but hasn't been assigned yet. A tracked surge index only ever
   * needs placing once -- {@code existingIndices} already reflects any assignment {@link
   * #handleSurge} itself didn't touch this tick, so this never re-places an already-live surge
   * instance.
   */
  private List<Integer> indicesNeedingPlacement(
      DeploymentSpec spec, int replicas, Set<Integer> existingIndices) {
    List<Integer> indices = new ArrayList<>(missingIndices(replicas, existingIndices));
    for (int surgeIndex : store.getSurgeIndices(spec.name()).keySet()) {
      if (!existingIndices.contains(surgeIndex)) {
        indices.add(surgeIndex);
      }
    }
    return indices;
  }

  /**
   * {@code alsoRunningThisDeployment} folds in nodes this same reconciliation tick has already
   * chosen for {@code deploymentName} but not yet committed to the store -- see {@link
   * #placeInstances}'s own rationale for why that's necessary for anti-affinity to work at all
   * across a single multi-replica placement batch.
   */
  private List<NodeCandidate> buildCandidates(
      String deploymentName, Set<String> alsoRunningThisDeployment) {
    Set<String> nodesAlreadyRunningThisDeployment = new HashSet<>(alsoRunningThisDeployment);
    // Every distinct tenantId already assigned to each node, across every deployment (not just
    // this one) -- the scheduler needs the full picture to enforce node-level tenant segregation
    // for Tier 2/3 placements.
    Map<String, Set<String>> tenantsByNode = new HashMap<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      if (assignment.deploymentName().equals(deploymentName)) {
        nodesAlreadyRunningThisDeployment.add(assignment.nodeId());
      }
      store
          .getDeployment(assignment.deploymentName())
          .flatMap(DeploymentSpec::tenantId)
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
        // A node that has stopped heartbeating is not merely a neutral candidate, it is an
        // attractive one: its last report is frozen at whatever capacity it had while alive, and
        // ReplicaCountReconciler has just released its assignments, so it looks like the emptiest
        // machine in the cluster. Placing here would re-place an instance onto a machine that is
        // not answering -- and since that placement is never confirmed, the two reconcilers would
        // trade release and re-place forever while the deployment stays down.
        continue;
      }
      candidates.add(
          new NodeCandidate(
              registration.nodeId(),
              registration.capabilities(),
              heartbeat.get().heartbeat().capacity(),
              nodesAlreadyRunningThisDeployment.contains(registration.nodeId()),
              tenantsByNode.getOrDefault(registration.nodeId(), Set.of()),
              store.isNodeCordoned(registration.nodeId())));
    }
    return candidates;
  }

  private boolean hasGoneDark(ObservedHeartbeat observed, Instant now) {
    return Duration.between(observed.receivedAt(), now).compareTo(nodeDarkTimeout) > 0;
  }
}
