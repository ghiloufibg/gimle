package com.gimle.controlplane.preview;

import com.gimle.controlplane.reconcile.DeploymentReconciler;
import com.gimle.controlplane.schedule.NodeCandidate;
import com.gimle.controlplane.schedule.NodeCandidateSource;
import com.gimle.controlplane.schedule.Scheduler;
import com.gimle.core.exception.GimleSchedulingException;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.StoreReader;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Answers "where would this workload's replicas land right now?" by running the identical {@link
 * Scheduler} calls the reconciler for that workload kind makes, against the identical {@link
 * NodeCandidate} list its own {@link NodeCandidateSource} builds -- reading the store, proposing
 * nothing.
 *
 * <p>Sharing the scheduler outright rather than reimplementing its filters is what makes the
 * forecast worth anything: {@link Scheduler#place} is a pure function of its arguments (it never
 * touches the store, never mutates a candidate, and reports a rejection by throwing rather than by
 * recording anything), so calling it here produces the same decision the reconciler will reach on
 * its next tick, including the resource dimension and shortfall its failure message names.
 *
 * <p>Only the indices a submission would <em>newly</em> need placed are evaluated: an index already
 * assigned to a live node is not re-placed by any reconciler, so forecasting it would invent work
 * that will not happen. That is also why the forecast can legitimately be empty -- a replica-count
 * cut, or a change to a field placement does not depend on, needs no new placement at all.
 */
public final class WorkloadPlacementPreview {

  private final StoreReader store;
  private final Scheduler scheduler;
  private final NodeCandidateSource candidateSource;

  public WorkloadPlacementPreview(StoreReader store, Scheduler scheduler, Clock clock) {
    this.store = store;
    this.scheduler = scheduler;
    this.candidateSource =
        new NodeCandidateSource(store, DeploymentReconciler.DEFAULT_NODE_DARK_TIMEOUT, clock);
  }

  /**
   * {@link Optional#empty()} for a workload kind that is never itself placed -- today only {@link
   * CronJobSpec}, a schedule that materializes an ordinary Job on each firing and so has no
   * replicas of its own to forecast.
   */
  public Optional<PlacementForecast> forecast(WorkloadSpec spec, ModuleDescriptor descriptor) {
    return switch (spec) {
      case DeploymentSpec deployment -> Optional.of(forecastDeployment(deployment, descriptor));
      case StatefulSetSpec statefulSet -> Optional.of(forecastStatefulSet(statefulSet, descriptor));
      case JobSpec job -> Optional.of(forecastJob(job, descriptor));
      case DaemonSetSpec daemonSet -> Optional.of(forecastDaemonSet(daemonSet, descriptor));
      case CronJobSpec ignored -> Optional.empty();
    };
  }

  /**
   * The autoscaler's own effective count stands in for the submitted {@code replicas} exactly as
   * {@code DeploymentReconciler} lets it, so a deployment under an active autoscale policy is
   * forecast at the count that will actually be placed rather than the one written in the manifest.
   */
  private PlacementForecast forecastDeployment(DeploymentSpec spec, ModuleDescriptor descriptor) {
    int replicas = store.getEffectiveReplicas(spec.tenantId(), spec.name()).orElse(spec.replicas());
    Set<Integer> assignedIndices = new HashSet<>();
    Set<String> occupiedNodes = new HashSet<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.tenantId(), spec.name())) {
      assignedIndices.add(assignment.instanceIndex());
      occupiedNodes.add(assignment.nodeId());
    }
    return placeMissingIndices(
        spec.name(),
        replicas,
        assignedIndices,
        occupiedNodes,
        descriptor,
        spec.placement(),
        spec.tenantId(),
        Optional.empty());
  }

  /**
   * A StatefulSet index that has already been bound to a node stays bound to it: the sticky node is
   * handed straight to {@link Scheduler#place}, which then answers only "is that one node still
   * eligible?" rather than choosing freely. A never-placed index has no binding and is scheduled
   * like any other replica.
   */
  private PlacementForecast forecastStatefulSet(StatefulSetSpec spec, ModuleDescriptor descriptor) {
    Set<Integer> assignedIndices = new HashSet<>();
    Set<String> occupiedNodes = new HashSet<>();
    for (StatefulSetAssignment assignment :
        store.listStatefulSetAssignmentsFor(spec.tenantId(), spec.name())) {
      assignedIndices.add(assignment.instanceIndex());
      occupiedNodes.add(assignment.nodeId());
    }
    List<PlacementForecast.Placement> placements = new ArrayList<>();
    List<PlacementForecast.Failure> failures = new ArrayList<>();
    Set<String> chosenSoFar = new HashSet<>(occupiedNodes);
    int evaluated = 0;
    for (int index = 0; index < spec.replicas(); index++) {
      if (assignedIndices.contains(index)) {
        continue;
      }
      evaluated++;
      Optional<String> sticky = store.getStatefulSetIndexNode(spec.tenantId(), spec.name(), index);
      placeOne(
          spec.name(),
          index,
          descriptor,
          spec.placement(),
          spec.tenantId(),
          sticky,
          chosenSoFar,
          placements,
          failures);
    }
    return new PlacementForecast(evaluated, placements, failures);
  }

  /** A Job commits exactly one attempt at a time, placed the same way a single replica is. */
  private PlacementForecast forecastJob(JobSpec spec, ModuleDescriptor descriptor) {
    Set<String> occupiedNodes = new HashSet<>();
    for (JobRun run : store.listJobRunsFor(spec.tenantId(), spec.name())) {
      occupiedNodes.add(run.nodeId());
    }
    List<PlacementForecast.Placement> placements = new ArrayList<>();
    List<PlacementForecast.Failure> failures = new ArrayList<>();
    placeOne(
        spec.name(),
        0,
        descriptor,
        spec.placement(),
        spec.tenantId(),
        Optional.empty(),
        new HashSet<>(occupiedNodes),
        placements,
        failures);
    return new PlacementForecast(1, placements, failures);
  }

  /**
   * A DaemonSet is not bin-packed at all -- it gets one instance on every eligible node -- so its
   * forecast is {@link Scheduler#eligibleNodes}' own survivor list, with index {@code 0} on each,
   * matching the single-index assignment {@code DaemonSetReconciler} writes per node. Zero eligible
   * nodes is reported as an unplaceable forecast rather than an empty one: a DaemonSet that would
   * run nowhere is exactly the typo'd taint or required label an operator wants to catch here.
   */
  private PlacementForecast forecastDaemonSet(DaemonSetSpec spec, ModuleDescriptor descriptor) {
    List<NodeCandidate> eligible =
        scheduler.eligibleNodes(
            descriptor.isolationTier(),
            spec.placement().antiAffinityAcrossNodes(),
            spec.tenantId(),
            spec.placement().requiredNodeLabels().orElse(Set.of()),
            spec.tolerateAllTaints(),
            candidateSource.candidates(Set.of()));
    if (eligible.isEmpty()) {
      return new PlacementForecast(
          0,
          List.of(),
          List.of(
              new PlacementForecast.Failure(
                  0,
                  "no registered node is currently eligible to run this DaemonSet"
                      + " (checked isolation tier, cordon, node taints and required labels)")));
    }
    List<PlacementForecast.Placement> placements = new ArrayList<>();
    for (NodeCandidate candidate : eligible) {
      placements.add(new PlacementForecast.Placement(0, candidate.nodeId()));
    }
    return new PlacementForecast(eligible.size(), placements, List.of());
  }

  private PlacementForecast placeMissingIndices(
      String name,
      int replicas,
      Set<Integer> assignedIndices,
      Set<String> occupiedNodes,
      ModuleDescriptor descriptor,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> stickyNodeId) {
    List<PlacementForecast.Placement> placements = new ArrayList<>();
    List<PlacementForecast.Failure> failures = new ArrayList<>();
    Set<String> chosenSoFar = new HashSet<>(occupiedNodes);
    int evaluated = 0;
    for (int index = 0; index < replicas; index++) {
      if (assignedIndices.contains(index)) {
        continue;
      }
      evaluated++;
      placeOne(
          name,
          index,
          descriptor,
          placement,
          tenantId,
          stickyNodeId,
          chosenSoFar,
          placements,
          failures);
    }
    return new PlacementForecast(evaluated, placements, failures);
  }

  /**
   * {@code chosenSoFar} accumulates the nodes this same forecast has already handed out, exactly as
   * the reconciler accumulates them within one placement pass -- without it, anti-affinity would
   * score every index against the identical "nothing placed anywhere yet" snapshot and predict them
   * all onto the one best-scoring node, which is not what the reconciler will actually do.
   */
  private void placeOne(
      String name,
      int index,
      ModuleDescriptor descriptor,
      PlacementConstraints placement,
      Optional<String> tenantId,
      Optional<String> stickyNodeId,
      Set<String> chosenSoFar,
      List<PlacementForecast.Placement> placements,
      List<PlacementForecast.Failure> failures) {
    List<NodeCandidate> candidates = candidateSource.candidates(chosenSoFar);
    try {
      String nodeId =
          scheduler.place(
              name,
              index,
              descriptor.isolationTier(),
              descriptor.resourceRequest(),
              placement.antiAffinityAcrossNodes(),
              tenantId,
              placement.requiredNodeLabels().orElse(Set.of()),
              stickyNodeId,
              candidates);
      chosenSoFar.add(nodeId);
      placements.add(new PlacementForecast.Placement(index, nodeId));
    } catch (GimleSchedulingException e) {
      failures.add(new PlacementForecast.Failure(index, String.valueOf(e.getMessage())));
    }
  }
}
