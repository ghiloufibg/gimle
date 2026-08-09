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
  // A private lock object, not `synchronized` on `this`: this instance is reachable from outside
  // the class (returned by the public static ofThisMachine() factory and held by callers such as
  // the control-plane API-server integration), so locking on `this` would let any of that external
  // code synchronize on the same monitor and stall assignment/release.
  private final Object lock = new Object();

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
  public boolean tryAssign(String workerId, ResourceSpec limit) {
    synchronized (lock) {
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
  }

  public void release(String workerId) {
    synchronized (lock) {
      assigned.remove(workerId);
    }
  }

  public Snapshot snapshot() {
    synchronized (lock) {
      long assignedMemory = assigned.values().stream().mapToLong(ResourceSpec::memoryBytes).sum();
      long assignedCpu = assigned.values().stream().mapToLong(ResourceSpec::cpuMillicores).sum();
      return new Snapshot(totalMemoryBytes, assignedMemory, totalCpuMillicores, assignedCpu);
    }
  }

  public record Snapshot(
      long totalMemoryBytes,
      long assignedMemoryBytes,
      long totalCpuMillicores,
      long assignedCpuMillicores) {}
}
