package com.gimle.worker;

import java.util.Optional;

/**
 * A hosted module's placement identity, as reported by the agent over the control channel
 * (log-explorer-design.md §3) -- distinct from bare {@link com.gimle.core.module.ModuleId} because
 * the same module+version could in principle back two different deployments colocated in one worker
 * JVM under Tier 1 density, which {@code ModuleId} alone can't distinguish.
 */
public record InstanceIdentity(
    String deploymentName, int instanceIndex, Optional<String> tenantId) {}
