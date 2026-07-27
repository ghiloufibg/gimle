package com.gimle.agent;

import com.gimle.core.module.ResourceSpec;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks this machine's total resource capacity against what is currently assigned to spawned
 * workers, exposing the result as a plain in-process query rather than a network endpoint --
 * callers such as the control-plane API-server integration read the snapshot directly.
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
