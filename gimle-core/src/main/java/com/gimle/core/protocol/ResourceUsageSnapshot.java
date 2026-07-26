package com.gimle.core.protocol;

/**
 * A node's total machine capacity against what's currently assigned across its supervised workers,
 * at the moment a heartbeat (see {@link NodeHeartbeat}) was sent. Structurally identical to {@code
 * gimle-agent}'s own {@code CapacityTracker.Snapshot} -- moved here so both the agent (producer)
 * and the control plane (consumer) share one type instead of the agent mapping its own snapshot
 * into a shape the control plane separately defines.
 */
public record ResourceUsageSnapshot(
    long totalMemoryBytes,
    long assignedMemoryBytes,
    long totalCpuMillicores,
    long assignedCpuMillicores) {}
