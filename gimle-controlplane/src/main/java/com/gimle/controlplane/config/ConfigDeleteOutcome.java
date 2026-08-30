package com.gimle.controlplane.config;

/**
 * One {@link ConfigVersionStore#delete} attempt's outcome. {@code NotFound} is not an error --
 * deleting a key that never existed is the same idempotent no-op every other resource kind's delete
 * already is here, it simply mints no new ledger version since nothing changed.
 */
public sealed interface ConfigDeleteOutcome {

  record Deleted(int version) implements ConfigDeleteOutcome {}

  record NotFound() implements ConfigDeleteOutcome {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigDeleteOutcome {}
}
