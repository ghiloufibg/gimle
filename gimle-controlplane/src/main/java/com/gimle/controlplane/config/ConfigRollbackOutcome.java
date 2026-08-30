package com.gimle.controlplane.config;

import java.util.Optional;

/**
 * One {@link ConfigVersionStore#rollback} attempt's outcome. {@code Applied} is itself the result
 * of a brand-new ledger version being minted -- a rollback never rewrites the target version or
 * anything stamped after it, the same "restore = re-apply as a new revision" semantics {@code
 * com.gimle.fafnir.secretmap.SecretMapStore#rollback} documents for SecretMap. {@code value} is
 * empty exactly when {@code deleted} is {@code true}, mirroring {@link ConfigVersion}'s own
 * convention for a tombstone.
 */
public sealed interface ConfigRollbackOutcome {

  record Applied(int version, Optional<String> value, boolean deleted)
      implements ConfigRollbackOutcome {}

  record TargetNotFound() implements ConfigRollbackOutcome {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigRollbackOutcome {}
}
