package com.gimle.controlplane.configmap;

import java.util.Map;

/**
 * One {@link ConfigMapStore#put}/{@link ConfigMapStore#patch} attempt's outcome. A version conflict
 * or contended-write giveup is an expected, structured outcome an HTTP caller needs to branch on --
 * not an exceptional condition -- so this is a sealed result type, mirroring {@code
 * com.gimle.controlplane.admission.AdmissionDecision}'s own Allow/Reject shape, rather than a
 * thrown exception.
 */
public sealed interface ConfigMapWriteResult {

  record Written(int version) implements ConfigMapWriteResult {}

  /**
   * The caller's {@code expectedVersion} was stale -- carries the current state back so a caller
   * can decide whether to retry against it, without a second round trip to fetch it.
   */
  record VersionConflict(int currentVersion, Map<String, String> currentData)
      implements ConfigMapWriteResult {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements ConfigMapWriteResult {}
}
