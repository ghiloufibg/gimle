package com.gimle.os;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import java.util.List;

/**
 * Applies a module's resource request/limit to the worker JVM that will host it. Today exactly one
 * implementation exists ({@link com.gimle.os.portable.PortableJvmFlagsResourceLimiter}), running
 * identically on every OS. This interface exists because portable JVM-flag enforcement
 * (-Xmx/ActiveProcessorCount) and kernel-level enforcement (cgroup v2 on Linux) are genuinely
 * different strategies with different guarantees, and more than one is expected to coexist — so
 * callers are written against it and never branch on platform themselves; a future kernel-level
 * implementation drops in without touching a caller.
 */
public interface ResourceLimiter {

  boolean supports(IsolationTier tier);

  ResourceLimitHandle prepare(String workerId, ResourceSpec limit);

  List<String> jvmFlags(ResourceLimitHandle handle);

  void release(ResourceLimitHandle handle);
}
