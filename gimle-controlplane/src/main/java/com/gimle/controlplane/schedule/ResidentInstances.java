package com.gimle.controlplane.schedule;

import com.gimle.controlplane.andvari.ArtifactResolver;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fills in each candidate node's current occupants, which {@link Scheduler#preemption} needs and
 * nothing else does.
 *
 * <p>Deliberately separate from {@link NodeCandidateSource} and never folded into it: answering
 * "who is on this node, at what priority, holding what reservation" means resolving a module
 * descriptor per distinct deployment, and paying that on every reconcile tick to serve a case that
 * only arises once the cluster is out of room would be a real cost for no benefit. Callers build
 * ordinary candidates first, and only enrich them after a placement has actually failed.
 *
 * <p>A resident whose artifact cannot be resolved right now is skipped rather than guessed at. The
 * consequence is conservative in the right direction: an unresolvable instance is never chosen as a
 * victim, so preemption under-evicts rather than evicting something whose reservation it could not
 * actually confirm.
 */
public final class ResidentInstances {

  private ResidentInstances() {}

  /**
   * The same candidates, each carrying the Deployment instances currently assigned to it. Only
   * Deployment assignments are considered: a StatefulSet instance is pinned to its node by a local
   * volume that eviction cannot move, a DaemonSet instance exists precisely because its node does,
   * and a Job run is already finite -- none of the three is a thing preemption may reclaim, so
   * offering them as victims would be offering something the platform cannot honour.
   */
  public static List<NodeCandidate> attach(
      StoreReader store, ArtifactResolver artifactResolver, List<NodeCandidate> candidates) {
    Map<String, List<ResidentInstance>> byNode = new HashMap<>();
    Map<String, Optional<ResidentTemplate>> templates = new HashMap<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      Optional<ResidentTemplate> template =
          templates.computeIfAbsent(
              templateKey(assignment), unused -> templateFor(store, artifactResolver, assignment));
      if (template.isEmpty()) {
        continue;
      }
      byNode
          .computeIfAbsent(assignment.nodeId(), unused -> new ArrayList<>())
          .add(
              new ResidentInstance(
                  assignment.deploymentName(),
                  assignment.instanceIndex(),
                  assignment.tenantId(),
                  template.get().priority(),
                  template.get().artifact().descriptor().resourceRequest()));
    }

    List<NodeCandidate> enriched = new ArrayList<>(candidates.size());
    for (NodeCandidate candidate : candidates) {
      enriched.add(
          new NodeCandidate(
              candidate.nodeId(),
              candidate.capabilities(),
              candidate.capacity(),
              candidate.alreadyRunsThisDeployment(),
              candidate.taints(),
              candidate.cordoned(),
              byNode.getOrDefault(candidate.nodeId(), List.of()),
              candidate.tier2Tenants()));
    }
    return enriched;
  }

  /** Cached per (tenant, deployment): every replica of one deployment shares both fields. */
  private static String templateKey(InstanceAssignment assignment) {
    return assignment.tenantId().orElse("") + "/" + assignment.deploymentName();
  }

  private static Optional<ResidentTemplate> templateFor(
      StoreReader store, ArtifactResolver artifactResolver, InstanceAssignment assignment) {
    Optional<DeploymentSpec> spec =
        store.getDeployment(assignment.tenantId(), assignment.deploymentName());
    if (spec.isEmpty()) {
      return Optional.empty();
    }
    Optional<ModuleArtifact> artifact =
        artifactResolver.resolveIfPossible(spec.get().artifactPath(), spec.get().moduleId());
    return artifact.map(
        resolved -> new ResidentTemplate(spec.get().placement().priority(), resolved));
  }

  private record ResidentTemplate(int priority, ModuleArtifact artifact) {}
}
