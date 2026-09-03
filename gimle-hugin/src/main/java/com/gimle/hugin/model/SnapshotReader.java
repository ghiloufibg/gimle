package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.protocol.Json;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    Map<WorkloadKind, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
    for (WorkloadKind kind : WorkloadKind.values()) {
      byKind.put(kind, reader.getList(kind.route()));
    }

    List<InstanceRow> instances = new ArrayList<>();
    List<WorkloadRow> workloads = new ArrayList<>();
    byKind.forEach(
        (kind, payloads) -> {
          instances.addAll(readInstances(kind, payloads));
          workloads.addAll(readWorkloads(kind, payloads));
        });
    instances.sort(
        Comparator.comparing((InstanceRow row) -> row.deploymentName())
            .thenComparingInt(InstanceRow::instanceIndex));
    workloads.sort(Comparator.comparing(WorkloadRow::name));

    Map<String, Integer> instancesPerNode = new HashMap<>();
    for (InstanceRow instance : instances) {
      instancesPerNode.merge(instance.nodeId(), 1, Integer::sum);
    }
    return new ClusterSnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        readNodes(instancesPerNode),
        instances,
        workloads,
        Optional.empty());
  }

  /**
   * The workload-level half of the same {@code /deployments} response the instance rows come from:
   * what each workload asked for, against what the control plane managed to place and what its
   * tenant's own quota and limit-range policy had to say about it.
   */
  private static List<WorkloadRow> readWorkloads(
      final WorkloadKind kind, final List<Map<String, Object>> deployments) {
    List<WorkloadRow> rows = new ArrayList<>();
    for (Map<String, Object> deployment : deployments) {
      Map<String, Object> spec = object(deployment.get("spec"));
      String name = string(spec.get("name"));
      if (name.isBlank()) {
        continue;
      }
      int placed = array(deployment.get("instances")).size();
      rows.add(
          new WorkloadRow(
              kind,
              optionalString(spec.get("tenantId")),
              name,
              (int) number(spec.get("replicas")),
              placed,
              (int) number(deployment.get("unplacedCount")),
              Boolean.TRUE.equals(deployment.get("quotaViolating")),
              Boolean.TRUE.equals(deployment.get("limitRangeViolating")),
              optionalString(deployment.get("limitRangeViolationReason"))));
    }
    return rows;
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

  private static List<InstanceRow> readInstances(
      final WorkloadKind kind, final List<Map<String, Object>> deployments) {
    List<InstanceRow> rows = new ArrayList<>();
    for (Map<String, Object> deployment : deployments) {
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
                kind,
                nodeId,
                instance.get("observation")));
      }
    }
    return rows;
  }

  private static InstanceRow instanceRow(
      final InstanceKey key,
      final WorkloadKind kind,
      final String nodeId,
      final Object rawObservation) {
    if (!(rawObservation instanceof Map<?, ?>)) {
      // Placed, but its node hasn't reported on it yet. PENDING is Hugin's own word for that gap:
      // it is deliberately not one of the platform's lifecycle states, because the instance is not
      // in any of them yet as far as this cluster knows.
      return new InstanceRow(
          key,
          kind,
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
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Map.of(),
          0L);
    }
    Map<String, Object> observation = object(rawObservation);
    return new InstanceRow(
        key,
        kind,
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
        optionalString(observation.get("workerId")),
        isolationTier(observation.get("isolationTier")),
        resourceLimit(observation.get("resourceLimit")),
        ports(observation.get("ports")),
        number(observation.get("volumeUsageBytes")));
  }

  /** Port names to numbers, dropping any entry whose value is not a number this build can use. */
  private static Map<String, Integer> ports(final Object rawPorts) {
    Map<String, Integer> ports = new LinkedHashMap<>();
    object(rawPorts)
        .forEach(
            (name, value) -> {
              if (value instanceof Number port) {
                ports.put(name, port.intValue());
              }
            });
    return Map.copyOf(ports);
  }

  private static Optional<IsolationTier> isolationTier(final Object rawTier) {
    String name = string(rawTier);
    for (IsolationTier tier : IsolationTier.values()) {
      if (tier.name().equals(name)) {
        return Optional.of(tier);
      }
    }
    return Optional.empty();
  }

  /**
   * A limit whose quantities this build cannot parse is dropped rather than shown as text: the
   * detail pane reads it back as bytes and millicores, so a value it cannot convert would be a
   * label with no number behind it.
   */
  private static Optional<ResourceSpec> resourceLimit(final Object rawLimit) {
    Map<String, Object> limit = object(rawLimit);
    String memory = string(limit.get("memory"));
    String cpu = string(limit.get("cpu"));
    if (memory.isBlank() || cpu.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ResourceSpec(memory, cpu));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
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
