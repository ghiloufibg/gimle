package com.gimle.os.portable;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import java.util.List;

/**
 * The guaranteed-minimum, and today the only, resource limiter: no cgroup, no live kernel
 * enforcement, runs identically on Linux/macOS/Windows. Derives {@code -Xmx} and {@code
 * -XX:ActiveProcessorCount} from the requested {@link ResourceSpec} and relies on the JVM to
 * self-limit — weaker than kernel-level enforcement (a runaway native allocation isn't caught),
 * which is exactly what "JVM-level limits" means. Kernel-level enforcement is a deliberately
 * deferred, later addition, not a parallel implementation built alongside this one.
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
  public List<String> jvmFlags(ResourceLimitHandle handle) {
    ResourceSpec limit = handle.limit();
    long memoryBytes = limit.memoryBytes();
    int activeProcessors = (int) Math.max(1, Math.ceilDiv(limit.cpuMillicores(), 1000L));
    return List.of("-Xmx" + memoryBytes, "-XX:ActiveProcessorCount=" + activeProcessors);
  }

  @Override
  public void release(ResourceLimitHandle handle) {
    // Nothing to release: no cgroup, no filesystem state, no live resources held.
  }
}
