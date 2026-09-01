package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link ClusterSnapshot} out of {@code GET /nodes} and {@code GET /deployments} -- the
 * two responses the {@code get nodes} and {@code get deployments} verbs already read, parsed here
 * into the flat rows the view draws instead of the table cells those verbs print.
 *
 * <p>Every field is read defensively. A response missing a field an older or newer control plane
 * doesn't send degrades that one column, never the whole poll: an operator watching a cluster
 * settle is exactly who can least afford the view to go blank over an absent number.
 */
public final class SnapshotReader {

  private final ClusterReader reader;

  public SnapshotReader(final ClusterReader reader) {
    this.reader = reader;
  }

  public ClusterSnapshot read() {
    List<InstanceRow> instances = readInstances();
    Map<String, Integer> instancesPerNode = new HashMap<>();
    for (InstanceRow instance : instances) {
      instancesPerNode.merge(instance.nodeId(), 1, Integer::sum);
    }
    return new ClusterSnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        readNodes(instancesPerNode),
        instances,
        Optional.empty());
  }

  private List<NodeRow> readNodes(final Map<String, Integer> instancesPerNode) {
    List<NodeRow> rows = new ArrayList<>();
    for (Map<String, Object> node : reader.getList("/nodes")) {
      String nodeId = string(node.get("nodeId"));
      if (nodeId.isBlank()) {
        continue;
      }
      Map<String, Object> capacity = object(node.get("capacity"));
      rows.add(
          new NodeRow(
              nodeId,
              Boolean.TRUE.equals(node.get("cordoned")),
              number(capacity.get("assignedCpuMillicores")),
              number(capacity.get("totalCpuMillicores")),
              number(capacity.get("assignedMemoryBytes")),
              number(capacity.get("totalMemoryBytes")),
              instancesPerNode.getOrDefault(nodeId, 0),
              instant(node.get("lastHeartbeatAt"))));
    }
    rows.sort(Comparator.comparing(NodeRow::nodeId));
    return rows;
  }

  private List<InstanceRow> readInstances() {
    List<InstanceRow> rows = new ArrayList<>();
    for (Map<String, Object> deployment : reader.getList("/deployments")) {
      Map<String, Object> spec = object(deployment.get("spec"));
      String deploymentName = string(spec.get("name"));
      Optional<String> specTenantId = optionalString(spec.get("tenantId"));
      if (deploymentName.isBlank()) {
        continue;
      }
      for (Object rawInstance : array(deployment.get("instances"))) {
        Map<String, Object> instance = object(rawInstance);
        String nodeId = string(instance.get("nodeId"));
        if (nodeId.isBlank()) {
          continue;
        }
        rows.add(
            instanceRow(
                new InstanceKey(
                    specTenantId, deploymentName, (int) number(instance.get("instanceIndex"))),
                nodeId,
                instance.get("observation")));
      }
    }
    rows.sort(
        Comparator.comparing((InstanceRow row) -> row.deploymentName())
            .thenComparingInt(InstanceRow::instanceIndex));
    return rows;
  }

  private static InstanceRow instanceRow(
      final InstanceKey key, final String nodeId, final Object rawObservation) {
    if (!(rawObservation instanceof Map<?, ?>)) {
      // Placed, but its node hasn't reported on it yet. PENDING is Hugin's own word for that gap:
      // it is deliberately not one of the platform's lifecycle states, because the instance is not
      // in any of them yet as far as this cluster knows.
      return new InstanceRow(
          key,
          nodeId,
          false,
          "PENDING",
          false,
          false,
          0.0,
          0.0,
          0,
          0L,
          0L,
          Optional.empty(),
          Optional.empty());
    }
    Map<String, Object> observation = object(rawObservation);
    return new InstanceRow(
        key,
        nodeId,
        true,
        stringOrDefault(observation.get("lifecycleState"), "UNKNOWN"),
        Boolean.TRUE.equals(observation.get("alive")),
        Boolean.TRUE.equals(observation.get("ready")),
        decimal(observation.get("requestRatePerSecond")),
        decimal(observation.get("errorRatePerSecond")),
        (int) number(observation.get("queueDepth")),
        number(observation.get("memoryBytesUsed")),
        number(observation.get("cpuMillicoresUsed")),
        moduleCoordinate(observation.get("moduleId")),
        optionalString(observation.get("workerId")));
  }

  private static Optional<String> moduleCoordinate(final Object rawModuleId) {
    if (rawModuleId instanceof Map<?, ?> moduleId) {
      Object name = moduleId.get("name");
      Object version = moduleId.get("version");
      if (name != null && version != null) {
        return Optional.of(name + "@" + version);
      }
    }
    return Optional.empty();
  }

  private static Map<String, Object> object(final Object value) {
    return value instanceof Map<?, ?> ? Json.asObject(value) : Map.of();
  }

  private static List<?> array(final Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static long number(final Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static double decimal(final Object value) {
    return value instanceof Number n ? n.doubleValue() : 0.0;
  }

  private static String string(final Object value) {
    return value instanceof String s ? s : "";
  }

  private static String stringOrDefault(final Object value, final String fallback) {
    String text = string(value);
    return text.isBlank() ? fallback : text;
  }

  private static Optional<String> optionalString(final Object value) {
    String text = string(value);
    return text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static Optional<Instant> instant(final Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
