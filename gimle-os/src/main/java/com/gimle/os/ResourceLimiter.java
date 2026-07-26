package com.gimle.os;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.ResourceUsage;
import java.util.List;

/**
 * Applies a module's resource request/limit to the worker JVM that will host it. Phase 2 ships
 * exactly one implementation ({@link com.gimle.os.portable.PortableJvmFlagsResourceLimiter}),
 * running identically on every OS. This interface exists because the spec's own architecture names
 * multiple isolation strategies as a load-bearing, defining feature — not because a second
 * implementation is needed today — so callers are written against it and never branch on platform
 * themselves; a future kernel-level implementation drops in without touching a caller.
 */
public interface ResourceLimiter {

  boolean supports(IsolationTier tier);

  ResourceLimitHandle prepare(String workerId, ResourceSpec limit);

  List<String> jvm_flags(ResourceLimitHandle handle);

  ResourceUsage current_usage(ResourceLimitHandle handle);

  void release(ResourceLimitHandle handle);
}
