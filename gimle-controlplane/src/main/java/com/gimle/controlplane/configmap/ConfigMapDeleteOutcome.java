package com.gimle.controlplane.configmap;

/**
 * One {@link ConfigMapStore#delete} attempt's outcome. {@code NotFound} is not an error -- deleting
 * a name that never existed is the same idempotent no-op every other resource kind's delete already
 * is here, it simply mints no new ledger version since nothing changed.
 */
public sealed interface ConfigMapDeleteOutcome {

  record Deleted(int version) implements ConfigMapDeleteOutcome {}

  record NotFound() implements ConfigMapDeleteOutcome {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigMapDeleteOutcome {}
}
