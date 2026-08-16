package com.gimle.testkit.heimdall;

import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One immutable snapshot of the cluster's control-plane-visible state -- what {@code GET
 * /deployments} and {@code GET /nodes} reported at one instant, through one replica. Conditions are
 * predicates over this; the forensic report renders the last one seen.
 */
public record ClusterView(
    Instant capturedAt,
    int sourceReplica,
    Map<String, DeploymentView> deployments,
    Map<String, NodeView> nodes) {

  public ClusterView {
    deployments = Map.copyOf(deployments);
    nodes = Map.copyOf(nodes);
  }

  public record InstanceView(
      int instanceIndex, String nodeId, Optional<String> lifecycleState, Optional<String> version) {

    String summarize() {
      return "#"
          + instanceIndex
          + "@"
          + nodeId
          + " "
          + lifecycleState.orElse("UNOBSERVED")
          + version.map(v -> " v" + v).orElse("");
    }
  }

  public record DeploymentView(
      String name, int desiredReplicas, boolean quotaViolating, List<InstanceView> instances) {

    public DeploymentView {
      instances = List.copyOf(instances);
    }

    public long countInState(final String lifecycleState) {
      return instances.stream()
          .filter(i -> i.lifecycleState().map(lifecycleState::equals).orElse(false))
          .count();
    }

    public boolean allActive() {
      return !instances.isEmpty() && countInState("ACTIVE") == instances.size();
    }

    public boolean allActiveOnVersion(final String version, final int expectedCount) {
      if (instances.size() != expectedCount) {
        return false;
      }
      return instances.stream()
          .allMatch(
              i ->
                  i.lifecycleState().map("ACTIVE"::equals).orElse(false)
                      && i.version().map(version::equals).orElse(false));
    }

    String summarize() {
      final List<String> parts = new ArrayList<>();
      for (final InstanceView instance : instances) {
        parts.add(instance.summarize());
      }
      return name
          + ": desired "
          + desiredReplicas
          + ", ACTIVE="
          + countInState("ACTIVE")
          + (quotaViolating ? ", QUOTA-VIOLATING" : "")
          + " ["
          + String.join(", ", parts)
          + "]";
    }
  }

  public record NodeView(
      String nodeId, boolean cordoned, List<String> labels, Optional<Instant> lastHeartbeatAt) {

    public NodeView {
      labels = List.copyOf(labels);
    }

    String summarize() {
      return nodeId
          + (cordoned ? " (cordoned)" : "")
          + lastHeartbeatAt.map(at -> " heartbeat@" + at).orElse(" (no heartbeat yet)");
    }
  }

  /** Parses the two list responses into a view; malformed entries fail loudly, never silently. */
  static ClusterView fromJson(
      final Instant capturedAt,
      final int sourceReplica,
      final List<Object> deploymentsJson,
      final List<Object> nodesJson) {
    final Map<String, DeploymentView> deployments = new LinkedHashMap<>();
    for (final Object entry : deploymentsJson) {
      final Map<String, Object> status = Json.asObject(entry);
      final Map<String, Object> spec = Json.asObject(status.get("spec"));
      final String name = (String) spec.get("name");
      final List<InstanceView> instances = new ArrayList<>();
      for (final Map<String, Object> instance : Json.asObjectList(status.get("instances"))) {
        Optional<String> state = Optional.empty();
        Optional<String> version = Optional.empty();
        if (instance.get("observation") instanceof Map<?, ?> observation) {
          state = Optional.ofNullable((String) observation.get("lifecycleState"));
          if (observation.get("moduleId") instanceof Map<?, ?> moduleId) {
            version = Optional.ofNullable((String) moduleId.get("version"));
          }
        }
        instances.add(
            new InstanceView(
                ((Number) instance.get("instanceIndex")).intValue(),
                (String) instance.get("nodeId"),
                state,
                version));
      }
      deployments.put(
          name,
          new DeploymentView(
              name,
              ((Number) spec.get("replicas")).intValue(),
              Boolean.TRUE.equals(status.get("quotaViolating")),
              instances));
    }
    final Map<String, NodeView> nodes = new LinkedHashMap<>();
    for (final Object entry : nodesJson) {
      final Map<String, Object> node = Json.asObject(entry);
      final String nodeId = (String) node.get("nodeId");
      List<String> labels = List.of();
      if (node.get("capabilities") instanceof Map<?, ?> capabilities
          && capabilities.get("labels") instanceof List<?> rawLabels) {
        labels = rawLabels.stream().map(String.class::cast).toList();
      }
      final Optional<Instant> lastHeartbeatAt =
          Optional.ofNullable((String) node.get("lastHeartbeatAt")).map(Instant::parse);
      nodes.put(
          nodeId,
          new NodeView(nodeId, Boolean.TRUE.equals(node.get("cordoned")), labels, lastHeartbeatAt));
    }
    return new ClusterView(capturedAt, sourceReplica, deployments, nodes);
  }
}
