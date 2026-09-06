package com.gimle.controlplane.autoscale;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.controlplane.node.NodeFreshness;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.MutationSink;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Horizontal autoscaling: for every deployment carrying an {@link AutoscalePolicy}, computes an
 * ideal replica count per configured signal -- CPU utilization ({@code cpuMillicoresUsed} &divide;
 * the module descriptor's {@code resourceRequest.cpuMillicores()}, always evaluated) plus request
 * rate, error rate, and queue depth (each only when its own {@code AutoscalePolicy} target is
 * configured), averaged across every currently-{@code ready} instance. {@link
 * AutoscalePolicy.CombinationMode#WORST_SIGNAL} (the default) takes the highest (worst) one as the
 * basis for the effective replica count, the same "max wins across independently computed metrics"
 * approach Kubernetes' own HPA uses rather than blending units together; {@link
 * AutoscalePolicy.CombinationMode#WEIGHTED} instead blends every configured signal's own
 * observed/target ratio into a single weighted average first (see {@link #computeWeightedIdeal}),
 * an opt-in alternative that leaves every pre-existing policy's behavior unchanged. The result is
 * clamped to {@code [minReplicas, maxReplicas]} and adjusted by exactly one replica per tick toward
 * it rather than jumping straight there -- avoiding thrash on a single noisy sample, the same
 * reasoning {@code RestartTracker}'s backoff already applies to a different oscillation risk.
 * {@link com.gimle.controlplane.reconcile.DeploymentReconciler} reads this effective count in place
 * of the user-submitted {@code replicas} whenever a policy is present; this reconciler never
 * touches {@link com.gimle.mimir.store.InstanceAssignment}s itself.
 *
 * <p>One replica per tick bounds how fast a decision is acted on, but not how often the direction
 * may reverse -- a metric sitting on its own target would otherwise scale up, then down, then up
 * again forever. {@link AutoscalePolicy#scaleUpCooldown()}/{@link
 * AutoscalePolicy#scaleDownCooldown()} are the stabilization windows that stop that: a move in
 * either direction is suppressed until that direction's window has elapsed since the deployment's
 * last recorded scale event. That timestamp is read from and written to the store ({@code
 * StateMutation.PutDeploymentLastScale}, committed in the same batch as the replica-count change it
 * accounts for), never held on this object -- a reconciler field would reset on every control-plane
 * restart and mean nothing at all to the replica that takes over after a failover, which is exactly
 * the level-triggered property every reconciler here has to preserve. Clamping an out-of-range
 * stored count back into {@code [minReplicas, maxReplicas]} is never suppressed: it corrects the
 * count against the policy's own bounds (typically right after an operator edited them) rather than
 * acting on an observed signal, so a window that has not elapsed must not leave it out of range.
 *
 * <p>Every sibling reconciler that reads a node heartbeat for a scheduling/health decision (e.g.
 * {@link com.gimle.controlplane.reconcile.ReplicaCountReconciler}) gates on the heartbeat's own
 * {@link ObservedHeartbeat#receivedAt()} freshness before trusting it -- without that, a dead
 * node's last-known {@link InstanceObservation} values stay frozen and still marked ready in the
 * store until {@code ReplicaCountReconciler} actually evicts the stale assignment, and this
 * reconciler would keep averaging that frozen, no-longer-real data into every scale decision for
 * the whole window in between. {@link #readyInstanceObservations} applies the identical {@code
 * nodeDarkTimeout} gate.
 */
public final class AutoscaleReconciler {

  private static final Logger log = LoggerFactory.getLogger(AutoscaleReconciler.class);

  /** Matches every sibling reconciler's own default node-dark timeout in production wiring. */
  private static final Duration DEFAULT_NODE_DARK_TIMEOUT = Duration.ofSeconds(15);

  private final StoreReader store;
  private final MutationSink mutations;
  private final ArtifactResolver artifactResolver;
  private final NodeFreshness freshness;
  private final Clock clock;

  /** Test-only convenience: applies mutations directly, bypassing Raft replication entirely. */
  public AutoscaleReconciler(StateStore store) {
    this(store, mutation -> mutation.applyTo(store));
  }

  /** Local-artifact-only resolution -- the pre-registry behavior every existing test exercises. */
  public AutoscaleReconciler(StoreReader store, MutationSink mutations) {
    this(store, mutations, ArtifactResolver.localOnly());
  }

  public AutoscaleReconciler(
      StoreReader store, MutationSink mutations, ArtifactResolver artifactResolver) {
    this(store, mutations, artifactResolver, DEFAULT_NODE_DARK_TIMEOUT, Clock.systemUTC());
  }

  /**
   * Canonical constructor, mirroring {@code ReplicaCountReconciler}'s own {@code nodeDarkTimeout}/
   * {@code clock} shape -- {@code clock} is injectable so a test can drive heartbeat staleness
   * deterministically via {@code TestClock} in {@code gimle-core}'s test-jar instead of sleeping
   * past a real timeout.
   */
  public AutoscaleReconciler(
      StoreReader store,
      MutationSink mutations,
      ArtifactResolver artifactResolver,
      Duration nodeDarkTimeout,
      Clock clock) {
    this.store = store;
    this.mutations = mutations;
    this.artifactResolver = artifactResolver;
    this.freshness = new NodeFreshness(nodeDarkTimeout);
    this.clock = clock;
  }

  public void reconcileOnce() {
    for (DeploymentSpec spec : store.listDeployments()) {
      spec.autoscale()
          .ifPresent(
              policy -> {
                try {
                  reconcileWorkload(
                      spec.tenantId(),
                      spec.name(),
                      "deployment",
                      spec.artifactPath(),
                      spec.moduleId(),
                      spec.vessel(),
                      policy,
                      spec.replicas(),
                      () -> readyInstanceObservations(spec));
                } catch (RuntimeException e) {
                  // One deployment's failure (e.g. a GimleRaftException from mutations.propose
                  // during a store leader-election gap) must never abort the rest of this tick's
                  // deployments -- the next tick retries this one from the same full snapshot.
                  log.warn(
                      "autoscale reconcile of deployment {} failed: {}",
                      spec.name(),
                      e.getMessage(),
                      e);
                }
              });
    }
    // A StatefulSet's own OrderedReady placement/scale-down still reads replicas() (or, once an
    // autoscale policy is present, this same effectiveReplicas store entry) exactly the way
    // DeploymentReconciler already does -- this reconciler never touches assignments itself.
    for (StatefulSetSpec spec : store.listStatefulSetSpecs()) {
      spec.autoscale()
          .ifPresent(
              policy -> {
                try {
                  reconcileWorkload(
                      spec.tenantId(),
                      spec.name(),
                      "statefulset",
                      spec.artifactPath(),
                      spec.moduleId(),
                      spec.vessel(),
                      policy,
                      spec.replicas(),
                      () -> readyStatefulSetInstanceObservations(spec));
                } catch (RuntimeException e) {
                  log.warn(
                      "autoscale reconcile of statefulset {} failed: {}",
                      spec.name(),
                      e.getMessage(),
                      e);
                }
              });
    }
  }

  /**
   * The signal-collection-and-decision core shared by both a {@link DeploymentSpec} and a {@link
   * StatefulSetSpec}: every field either kind's own {@code reconcileOnce} branch needs is passed in
   * by value rather than the spec itself, since the two kinds share no common supertype beyond
   * {@code name()}/{@code tenantId()} -- see {@link com.gimle.mimir.manifest.WorkloadSpec}'s own
   * javadoc for why. {@code readyObservationsSupplier} is lazy: it's never invoked at all once an
   * unreadable artifact or a zero CPU request has already decided the outcome, sparing a
   * StatefulSet (which has its own assignment listing) an unnecessary heartbeat scan on every such
   * tick.
   */
  private void reconcileWorkload(
      Optional<String> tenantId,
      String name,
      String workloadKindLabel,
      String artifactPath,
      ModuleId moduleId,
      Optional<VesselSpec> vessel,
      AutoscalePolicy policy,
      int replicas,
      Supplier<List<InstanceObservation>> readyObservationsSupplier) {
    int currentEffective = store.getEffectiveReplicas(tenantId, name).orElse(replicas);

    ModuleDescriptor descriptor;
    try {
      descriptor = artifactResolver.resolve(artifactPath, moduleId, vessel).descriptor();
    } catch (RuntimeException e) {
      log.warn(
          "{} {} references an unreadable artifact {}; leaving its effective replica count"
              + " unchanged: {}",
          workloadKindLabel,
          name,
          artifactPath,
          e.getMessage());
      putEffectiveReplicas(tenantId, name, clamp(currentEffective, policy));
      return;
    }
    long cpuRequestMillicores = descriptor.resourceRequest().cpuMillicores();
    if (cpuRequestMillicores <= 0) {
      putEffectiveReplicas(tenantId, name, clamp(currentEffective, policy));
      return;
    }

    List<InstanceObservation> readyObservations = readyObservationsSupplier.get();
    if (readyObservations.isEmpty()) {
      // No signal yet (nothing ready/reporting): hold the current count rather than guessing.
      putEffectiveReplicas(tenantId, name, clamp(currentEffective, policy));
      return;
    }

    double averageUtilizationPercent =
        average(readyObservations, obs -> (obs.cpuMillicoresUsed() * 100.0) / cpuRequestMillicores);
    double averageRequestRate =
        average(readyObservations, InstanceObservation::requestRatePerSecond);
    // Error rate is evaluated as a percentage of that instance's own request volume (errors/sec
    // divided by requests/sec), not a raw errors/sec count -- consistent with the policy field's
    // own "Percent" name and the same per-instance-then-averaged shape CPU utilization already
    // uses. An instance with zero request volume contributes 0% rather than dividing by zero.
    double averageErrorRate = average(readyObservations, AutoscaleReconciler::errorRatePercent);
    double averageQueueDepth = average(readyObservations, obs -> (double) obs.queueDepth());

    int idealFromCpu =
        computeIdeal(
            currentEffective, averageUtilizationPercent, policy.targetCpuUtilizationPercent());
    // Each of these three is present only when its own policy target is configured -- an existing
    // CPU-only policy never evaluates them, matching pre-Part-C behavior exactly.
    OptionalInt idealFromRequestRate =
        policy.targetRequestRatePerSecond().isPresent()
            ? OptionalInt.of(
                computeIdeal(
                    currentEffective,
                    averageRequestRate,
                    policy.targetRequestRatePerSecond().getAsDouble()))
            : OptionalInt.empty();
    OptionalInt idealFromErrorRate =
        policy.targetErrorRatePercent().isPresent()
            ? OptionalInt.of(
                computeIdeal(
                    currentEffective,
                    averageErrorRate,
                    policy.targetErrorRatePercent().getAsDouble()))
            : OptionalInt.empty();
    OptionalInt idealFromQueueDepth =
        policy.targetQueueDepth().isPresent()
            ? OptionalInt.of(
                computeIdeal(
                    currentEffective, averageQueueDepth, policy.targetQueueDepth().getAsInt()))
            : OptionalInt.empty();

    int idealReplicas =
        switch (policy.combinationMode()) {
          case WORST_SIGNAL ->
              // Worst signal wins: each configured metric proposes its own ideal replica count
              // independently, and the highest one drives the decision -- the same approach
              // Kubernetes' own HPA takes across multiple metrics, rather than blending
              // differently-shaped signals together.
              Stream.of(
                      OptionalInt.of(idealFromCpu),
                      idealFromRequestRate,
                      idealFromErrorRate,
                      idealFromQueueDepth)
                  .filter(OptionalInt::isPresent)
                  .mapToInt(OptionalInt::getAsInt)
                  .max()
                  .orElse(currentEffective);
          case WEIGHTED ->
              computeWeightedIdeal(
                  currentEffective,
                  averageUtilizationPercent,
                  averageRequestRate,
                  averageErrorRate,
                  averageQueueDepth,
                  policy);
        };
    int clampedIdeal = clamp(idealReplicas, policy);

    // The bounds correction is computed first and separately from the signal-driven step, so the
    // cooldown below can suppress the step alone and still leave an out-of-range stored count
    // corrected on this very tick.
    int bounded = clamp(currentEffective, policy);
    int nextEffective = bounded;
    if (clampedIdeal > bounded) {
      nextEffective = clamp(bounded + 1, policy);
    } else if (clampedIdeal < bounded) {
      nextEffective = clamp(bounded - 1, policy);
    }

    if (nextEffective != bounded
        && withinCooldown(tenantId, name, policy, nextEffective > bounded)) {
      putEffectiveReplicas(tenantId, name, bounded);
      return;
    }
    if (nextEffective != currentEffective) {
      log.info(
          "{} {}: ideal replicas by signal (cpu={}, requestRate={}, errorRate={}, queueDepth={});"
              + " adjusting effective replicas {} -> {}",
          workloadKindLabel,
          name,
          idealFromCpu,
          idealFromRequestRate,
          idealFromErrorRate,
          idealFromQueueDepth,
          currentEffective,
          nextEffective);
      // The last-scale stamp rides the same batch as the change it accounts for: a stamp that
      // landed without its own replica-count change (or the reverse) would let the next tick
      // measure the stabilization window against something that never happened.
      mutations.proposeAll(
          List.of(
              new StateMutation.PutEffectiveReplicas(tenantId, name, nextEffective),
              new StateMutation.PutDeploymentLastScale(tenantId, name, clock.instant())));
      return;
    }
    putEffectiveReplicas(tenantId, name, nextEffective);
  }

  /**
   * Whether this workload's own stabilization window for {@code scalingUp}'s direction has yet to
   * elapse since its last recorded scale event. A workload that has never scaled has no window to
   * wait out, and a zero-length window never suppresses anything -- including when a replica's
   * clock reads slightly behind whichever one stamped the last event.
   */
  private boolean withinCooldown(
      Optional<String> tenantId, String name, AutoscalePolicy policy, boolean scalingUp) {
    Duration window = scalingUp ? policy.scaleUpCooldown() : policy.scaleDownCooldown();
    if (window.isZero()) {
      return false;
    }
    Optional<Instant> lastScale = store.getDeploymentLastScale(tenantId, name);
    if (lastScale.isEmpty()) {
      return false;
    }
    Duration sinceLastScale = Duration.between(lastScale.get(), clock.instant());
    boolean suppressed = sinceLastScale.compareTo(window) < 0;
    if (suppressed) {
      log.debug(
          "{}: {} suppressed, {} of the {} stabilization window elapsed since {}",
          name,
          scalingUp ? "scale-up" : "scale-down",
          sinceLastScale,
          window,
          lastScale.get());
    }
    return suppressed;
  }

  private static double errorRatePercent(InstanceObservation obs) {
    return obs.requestRatePerSecond() > 0
        ? (obs.errorRatePerSecond() * 100.0) / obs.requestRatePerSecond()
        : 0.0;
  }

  private static double average(
      List<InstanceObservation> observations, ToDoubleFunction<InstanceObservation> signal) {
    return observations.stream().mapToDouble(signal).average().orElse(0.0);
  }

  /**
   * Shared by every signal: how many replicas would bring {@code observedAverage} to {@code
   * target}.
   */
  private static int computeIdeal(int currentEffective, double observedAverage, double target) {
    return (int) Math.ceil(currentEffective * (observedAverage / target));
  }

  /**
   * {@link com.gimle.mimir.manifest.AutoscalePolicy.CombinationMode#WEIGHTED}'s alternative to
   * {@code WORST_SIGNAL}'s {@code max()}: every configured signal's own {@code observed/target}
   * ratio (the same ratio {@link #computeIdeal} multiplies by {@code currentEffective} and ceils
   * per signal) is instead weighted and averaged into one blended ratio first, weight defaulting to
   * {@code 1.0} when a signal is configured but its own weight is not -- then {@link #computeIdeal}
   * runs exactly once, on that blended ratio, rather than once per signal. CPU is always in the
   * blend, matching {@code WORST_SIGNAL}'s own "CPU always evaluated" rule above.
   */
  private static int computeWeightedIdeal(
      int currentEffective,
      double averageUtilizationPercent,
      double averageRequestRate,
      double averageErrorRate,
      double averageQueueDepth,
      AutoscalePolicy policy) {
    double weightedRatioSum =
        signalRatio(averageUtilizationPercent, policy.targetCpuUtilizationPercent())
            * policy.cpuWeight().orElse(1.0);
    double weightTotal = policy.cpuWeight().orElse(1.0);
    if (policy.targetRequestRatePerSecond().isPresent()) {
      weightedRatioSum +=
          signalRatio(averageRequestRate, policy.targetRequestRatePerSecond().getAsDouble())
              * policy.requestRateWeight().orElse(1.0);
      weightTotal += policy.requestRateWeight().orElse(1.0);
    }
    if (policy.targetErrorRatePercent().isPresent()) {
      weightedRatioSum +=
          signalRatio(averageErrorRate, policy.targetErrorRatePercent().getAsDouble())
              * policy.errorRateWeight().orElse(1.0);
      weightTotal += policy.errorRateWeight().orElse(1.0);
    }
    if (policy.targetQueueDepth().isPresent()) {
      weightedRatioSum +=
          signalRatio(averageQueueDepth, policy.targetQueueDepth().getAsInt())
              * policy.queueDepthWeight().orElse(1.0);
      weightTotal += policy.queueDepthWeight().orElse(1.0);
    }
    double blendedRatio = weightTotal > 0 ? weightedRatioSum / weightTotal : 1.0;
    return (int) Math.ceil(currentEffective * blendedRatio);
  }

  private static double signalRatio(double observedAverage, double target) {
    return observedAverage / target;
  }

  /**
   * Single choke point for every {@code reconcileWorkload} exit path (the main path and all three
   * early-return branches above), so every one of them gets the same guard: a replica count that
   * already matches what's stored costs nothing, even though this method is still called
   * unconditionally on every tick -- the level-triggered recompute-from-scratch behavior is
   * unchanged, only the redundant re-proposal of an already-correct value is skipped. An absent
   * stored value (a workload's very first tick) always proposes, seeding it exactly once.
   */
  private void putEffectiveReplicas(Optional<String> tenantId, String name, int replicas) {
    boolean alreadyCorrect =
        store
            .getEffectiveReplicas(tenantId, name)
            .map(current -> current == replicas)
            .orElse(false);
    if (alreadyCorrect) {
      return;
    }
    mutations.propose(new StateMutation.PutEffectiveReplicas(tenantId, name, replicas));
  }

  private static int clamp(int value, AutoscalePolicy policy) {
    return Math.max(policy.minReplicas(), Math.min(policy.maxReplicas(), value));
  }

  private List<InstanceObservation> readyInstanceObservations(DeploymentSpec spec) {
    Instant now = clock.instant();
    Instant observingSince = store.nodeObservationWindowStart();
    List<InstanceObservation> result = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.tenantId(), spec.name())) {
      store
          .getNodeHeartbeat(assignment.nodeId())
          .filter(
              observed -> !freshness.hasGoneDark(true, Optional.of(observed), observingSince, now))
          .map(ObservedHeartbeat::heartbeat)
          .map(NodeHeartbeat::instances)
          .orElse(List.of())
          .stream()
          .filter(
              obs ->
                  obs.deploymentName().equals(spec.name())
                      && obs.instanceIndex() == assignment.instanceIndex()
                      && obs.tenantId().equals(spec.tenantId())
                      && obs.ready())
          .findFirst()
          .ifPresent(result::add);
    }
    return result;
  }

  /** {@link #readyInstanceObservations}'s exact counterpart for a {@link StatefulSetSpec}. */
  private List<InstanceObservation> readyStatefulSetInstanceObservations(StatefulSetSpec spec) {
    Instant now = clock.instant();
    Instant observingSince = store.nodeObservationWindowStart();
    List<InstanceObservation> result = new ArrayList<>();
    for (StatefulSetAssignment assignment :
        store.listStatefulSetAssignmentsFor(spec.tenantId(), spec.name())) {
      store
          .getNodeHeartbeat(assignment.nodeId())
          .filter(
              observed -> !freshness.hasGoneDark(true, Optional.of(observed), observingSince, now))
          .map(ObservedHeartbeat::heartbeat)
          .map(NodeHeartbeat::instances)
          .orElse(List.of())
          .stream()
          .filter(
              obs ->
                  obs.deploymentName().equals(spec.name())
                      && obs.instanceIndex() == assignment.instanceIndex()
                      && obs.tenantId().equals(spec.tenantId())
                      && obs.ready())
          .findFirst()
          .ifPresent(result::add);
    }
    return result;
  }
}
