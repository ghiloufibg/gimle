package com.gimle.os.portable;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ResourceUsage;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import java.util.List;

/**
 * The guaranteed-minimum, and for Phase 2 the <em>only</em>, resource limiter: no cgroup, no live
 * kernel enforcement, runs identically on Linux/macOS/Windows. Derives {@code -Xmx} and {@code
 * -XX:ActiveProcessorCount} from the requested {@link ResourceSpec} and relies on the JVM to
 * self-limit — weaker than kernel-level enforcement (a runaway native allocation isn't caught),
 * which is exactly what "JVM-level limits" means. Kernel-level enforcement is a deliberately
 * deferred, later addition (see the Phase 2 design doc §2.4), not a parallel implementation built
 * alongside this one.
 */
public final class PortableJvmFlagsResourceLimiter implements ResourceLimiter {

  @Override
  public boolean supports(IsolationTier tier) {
    return tier == IsolationTier.TIER_1 || tier == IsolationTier.TIER_2;
  }

  @Override
  public ResourceLimitHandle prepare(String workerId, ResourceSpec limit) {
    return new ResourceLimitHandle(workerId, limit);
  }

  @Override
  public List<String> jvm_flags(ResourceLimitHandle handle) {
    ResourceSpec limit = handle.limit();
    long memoryBytes = limit.memory_bytes();
    int activeProcessors = (int) Math.max(1, Math.ceilDiv(limit.cpu_millicores(), 1000L));
    return List.of("-Xmx" + memoryBytes, "-XX:ActiveProcessorCount=" + activeProcessors);
  }

  /**
   * This limiter has no local process to introspect — the agent and the worker are separate JVMs,
   * and there is no live view into a worker's actual memory/CPU without the worker self-reporting
   * over the control channel. Always returns a zeroed reading; real usage tracking comes from the
   * worker's own {@code MetricsReport} messages, aggregated by the agent's capacity tracker, not
   * through this method.
   */
  @Override
  public ResourceUsage current_usage(ResourceLimitHandle handle) {
    return new ResourceUsage(0, 0);
  }

  @Override
  public void release(ResourceLimitHandle handle) {
    // Nothing to release: no cgroup, no filesystem state, no live resources held.
  }
}
