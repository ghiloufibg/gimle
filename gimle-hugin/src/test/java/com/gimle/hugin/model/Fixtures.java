package com.gimle.hugin.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Response shapes exactly as the control plane serves them, so a change to what the API returns
 * shows up here as a failing parse rather than as a column that quietly went blank.
 */
public final class Fixtures {

  private Fixtures() {}

  public static Map<String, Object> node(
      final String nodeId,
      final long assignedCpu,
      final long totalCpu,
      final long assignedMemoryGib,
      final long totalMemoryGib) {
    return node(
        nodeId, assignedCpu, totalCpu, assignedMemoryGib, totalMemoryGib, false, Instant.now());
  }

  public static Map<String, Object> node(
      final String nodeId,
      final long assignedCpu,
      final long totalCpu,
      final long assignedMemoryGib,
      final long totalMemoryGib,
      final boolean cordoned,
      final Instant lastHeartbeatAt) {
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", totalMemoryGib * 1024L * 1024L * 1024L);
    capacity.put("assignedMemoryBytes", assignedMemoryGib * 1024L * 1024L * 1024L);
    capacity.put("totalCpuMillicores", totalCpu);
    capacity.put("assignedCpuMillicores", assignedCpu);

    Map<String, Object> node = new LinkedHashMap<>();
    node.put("nodeId", nodeId);
    node.put("capabilities", Map.of("supportedTiers", List.of("TIER_1", "TIER_2")));
    node.put("cordoned", cordoned);
    node.put("taints", List.of());
    node.put("lastHeartbeatAt", lastHeartbeatAt.toString());
    node.put("capacity", capacity);
    return node;
  }

  public static Map<String, Object> deployment(
      final String name,
      final Optional<String> tenantId,
      final List<Map<String, Object>> instances) {
    Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("name", name);
    spec.put("moduleId", Map.of("name", name, "version", "1.0.0"));
    spec.put("artifactPath", "");
    spec.put("replicas", instances.size());
    tenantId.ifPresent(id -> spec.put("tenantId", id));

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", spec);
    status.put("instances", new ArrayList<>(instances));
    status.put("unplacedCount", 0);
    status.put("quotaViolating", false);
    status.put("limitRangeViolating", false);
    return status;
  }

  public static Map<String, Object> instance(
      final int index,
      final String nodeId,
      final String lifecycleState,
      final double requestRate,
      final double errorRate) {
    return instance(index, nodeId, lifecycleState, requestRate, errorRate, 0, 0L, 0L);
  }

  public static Map<String, Object> instance(
      final int index,
      final String nodeId,
      final String lifecycleState,
      final double requestRate,
      final double errorRate,
      final int queueDepth,
      final long memoryBytes,
      final long cpuMillicores) {
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("moduleId", Map.of("name", "module", "version", "1.0.0"));
    observation.put("lifecycleState", lifecycleState);
    observation.put("alive", !"FAILED".equals(lifecycleState));
    observation.put("ready", "ACTIVE".equals(lifecycleState));
    observation.put("requestRatePerSecond", requestRate);
    observation.put("errorRatePerSecond", errorRate);
    observation.put("queueDepth", queueDepth);
    observation.put("cpuMillicoresUsed", cpuMillicores);
    observation.put("memoryBytesUsed", memoryBytes);
    observation.put("workerId", "worker-4471");

    Map<String, Object> instance = new LinkedHashMap<>();
    instance.put("instanceIndex", index);
    instance.put("nodeId", nodeId);
    instance.put("observation", observation);
    return instance;
  }
}
