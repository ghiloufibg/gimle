package com.gimle.worker;

import java.util.Optional;

/**
 * A hosted module's placement identity, as reported by the agent over the control channel --
 * distinct from the artifact coordinate because the same module+version could in principle back two
 * different deployments colocated in one worker JVM under Tier 1 density, which the coordinate
 * alone can't distinguish.
 */
public record InstanceIdentity(
    String deploymentName, int instanceIndex, Optional<String> tenantId) {}
