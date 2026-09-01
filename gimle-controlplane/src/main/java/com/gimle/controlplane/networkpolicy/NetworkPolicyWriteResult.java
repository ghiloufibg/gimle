package com.gimle.controlplane.networkpolicy;

import com.gimle.mimir.manifest.NetworkPolicySpec;
import java.util.Optional;

/**
 * One {@link NetworkPolicyRegistry#put}/{@link NetworkPolicyRegistry#patch} attempt's outcome. A
 * stale version, a missing target, or a contended-write giveup are all expected, structured
 * outcomes an HTTP caller needs to branch on -- not exceptional conditions -- so this is a sealed
 * result type rather than a thrown exception, the same shape {@code
 * com.gimle.controlplane.configmap.ConfigMapWriteResult} already takes for the sibling
 * lease-guarded write path.
 */
public sealed interface NetworkPolicyWriteResult {

  record Written(NetworkPolicySpec spec) implements NetworkPolicyWriteResult {}

  /**
   * The caller's {@code expectedVersion} was stale -- carries the policy as it currently stands so
   * a caller can decide whether to retry against it without a second round trip. {@code current}
   * is absent when nothing is stored under the name at all, which is version {@code 0}.
   */
  record VersionConflict(int currentVersion, Optional<NetworkPolicySpec> current)
      implements NetworkPolicyWriteResult {}

  /**
   * A partial update named a policy that does not exist. Deliberately not a create: a policy is a
   * security control, and a typo in the name of one an operator meant to narrow should surface as
   * an error rather than quietly bring a new policy into being.
   */
  record NotFound() implements NetworkPolicyWriteResult {}

  /** Every attempt lost the write lease to another concurrent writer. */
  record WriteContention(int attempts) implements NetworkPolicyWriteResult {}
}
