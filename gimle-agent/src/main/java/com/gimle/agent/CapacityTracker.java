package com.gimle.agent;

import com.gimle.core.module.ResourceSpec;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The locally-queryable half of capacity reporting (design §4.4): Phase 2 has no control plane to
 * report *to*, so this only tracks total machine capacity against what's currently assigned to
 * spawned workers and exposes it as a plain in-process method -- not a network endpoint -- for
 * Phase 3's eventual API-server integration to call into.
 */
public final class CapacityTracker {

  private final long totalMemoryBytes;
  private final long totalCpuMillicores;
  private final Map<String, ResourceSpec> assigned = new ConcurrentHashMap<>();

  public CapacityTracker(long totalMemoryBytes, long totalCpuMillicores) {
    if (totalMemoryBytes <= 0) {
      throw new IllegalArgumentException("totalMemoryBytes must be positive: " + totalMemoryBytes);
    }
    if (totalCpuMillicores <= 0) {
      throw new IllegalArgumentException(
          "totalCpuMillicores must be positive: " + totalCpuMillicores);
    }
    this.totalMemoryBytes = totalMemoryBytes;
    this.totalCpuMillicores = totalCpuMillicores;
  }

  /**
   * {@code com.sun.management.OperatingSystemMXBean} is a standard part of every HotSpot-derived
   * JVM's {@code jdk.management} module (Linux, macOS, and Windows alike) -- a JDK API, not an
   * OS-specific mechanism this project branches on.
   */
  public static CapacityTracker ofThisMachine() {
    OperatingSystemMXBean osBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long totalMemory = osBean.getTotalMemorySize();
    long cpuMillicores = (long) Runtime.getRuntime().availableProcessors() * 1000L;
    return new CapacityTracker(totalMemory, cpuMillicores);
  }

  /** Assigns {@code limit} to {@code workerId} if doing so would not exceed total capacity. */
  public synchronized boolean tryAssign(String workerId, ResourceSpec limit) {
    if (assigned.containsKey(workerId)) {
      throw new IllegalStateException("worker " + workerId + " already has an assignment");
    }
    long assignedMemory = assigned.values().stream().mapToLong(ResourceSpec::memoryBytes).sum();
    long assignedCpu = assigned.values().stream().mapToLong(ResourceSpec::cpuMillicores).sum();
    if (assignedMemory + limit.memoryBytes() > totalMemoryBytes) {
      return false;
    }
    if (assignedCpu + limit.cpuMillicores() > totalCpuMillicores) {
      return false;
    }
    assigned.put(workerId, limit);
    return true;
  }

  public synchronized void release(String workerId) {
    assigned.remove(workerId);
  }

  public synchronized Snapshot snapshot() {
    long assignedMemory = assigned.values().stream().mapToLong(ResourceSpec::memoryBytes).sum();
    long assignedCpu = assigned.values().stream().mapToLong(ResourceSpec::cpuMillicores).sum();
    return new Snapshot(totalMemoryBytes, assignedMemory, totalCpuMillicores, assignedCpu);
  }

  public record Snapshot(
      long totalMemoryBytes,
      long assignedMemoryBytes,
      long totalCpuMillicores,
      long assignedCpuMillicores) {}
}
