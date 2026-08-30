package com.gimle.controlplane.config;

/**
 * One {@link ConfigVersionStore#put} attempt's outcome -- a sealed result type rather than a thrown
 * exception, mirroring {@code com.gimle.controlplane.configmap.ConfigMapWriteResult}'s own
 * reasoning: write contention is an expected, structured outcome an HTTP caller needs to branch on,
 * not an exceptional condition.
 */
public sealed interface ConfigWriteOutcome {

  record Written(int version) implements ConfigWriteOutcome {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigWriteOutcome {}
}
