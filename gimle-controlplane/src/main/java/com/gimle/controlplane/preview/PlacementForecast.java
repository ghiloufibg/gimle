package com.gimle.controlplane.preview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each replica a submission would newly need placed would land right now, and why any of them
 * would not land at all -- the forecast of the {@code unplacedCount} an operator would otherwise
 * only see by committing the manifest and reading the deployment status back afterwards.
 *
 * <p>Deliberately advisory: no workload kind's admission ever refuses a submission for being
 * unplaceable, because placement is a level-triggered property of the cluster at this instant, not
 * of the manifest -- a replica with nowhere to go today lands on its own once a node frees up or
 * joins. So an unplaceable forecast never turns a dry-run's verdict into a rejection; it reports
 * what the reconciler is about to be unable to do.
 *
 * <p>{@code failures} carries the scheduler's own {@code GimleSchedulingException} message for each
 * index -- which names the resource dimension, the shortfall, and the roomiest candidate node, the
 * remediation detail an operator wants before committing rather than after.
 */
public record PlacementForecast(
    int replicasEvaluated, List<Placement> placements, List<Failure> failures) {

  /** One index the scheduler would place, and the node it would choose. */
  public record Placement(int instanceIndex, String nodeId) {}

  /** One index the scheduler would refuse to place, with the reason it would give. */
  public record Failure(int instanceIndex, String reason) {}

  public PlacementForecast {
    placements = List.copyOf(placements);
    failures = List.copyOf(failures);
  }

  public boolean fullyPlaceable() {
    return failures.isEmpty();
  }

  public Map<String, Object> toJson() {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("replicasEvaluated", replicasEvaluated);
    json.put("placeable", placements.size());
    json.put("unplaceable", failures.size());
    List<Map<String, Object>> placementJson = new ArrayList<>();
    for (Placement placement : placements) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("instanceIndex", placement.instanceIndex());
      entry.put("nodeId", placement.nodeId());
      placementJson.add(entry);
    }
    json.put("placements", placementJson);
    List<Map<String, Object>> failureJson = new ArrayList<>();
    for (Failure failure : failures) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("instanceIndex", failure.instanceIndex());
      entry.put("reason", failure.reason());
      failureJson.add(entry);
    }
    json.put("failures", failureJson);
    return json;
  }
}
