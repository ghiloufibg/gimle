package com.gimle.controlplane.configmap;

import java.util.Map;

/**
 * One {@link ConfigMapStore#rollback} attempt's outcome. {@code Applied} is itself the result of a
 * brand-new ledger version being minted -- a rollback never rewrites the target version or anything
 * stamped after it, the same "restore = re-apply as a new revision" semantics {@code
 * com.gimle.fafnir.secretmap.SecretMapStore#rollback} documents for SecretMap. {@code data} is
 * empty exactly when {@code deleted} is {@code true}, mirroring {@link ConfigMapVersion}'s own
 * convention for a tombstone.
 */
public sealed interface ConfigMapRollbackOutcome {

  record Applied(int version, Map<String, String> data, boolean deleted)
      implements ConfigMapRollbackOutcome {}

  record TargetNotFound() implements ConfigMapRollbackOutcome {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigMapRollbackOutcome {}
}
