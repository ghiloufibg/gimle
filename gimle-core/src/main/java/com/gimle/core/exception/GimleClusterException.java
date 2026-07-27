package com.gimle.core.exception;

import java.util.List;

/**
 * A cluster-level operational failure with no per-request retry left to try: joining a cluster
 * whose every configured seed is unreachable, or a fabric service lookup exhausting every endpoint
 * the membership table knows about for an interface, cluster-wide -- the cluster-scoped analog of
 * {@link GimleSchedulingException}'s "no feasible placement." Never thrown for a merely local gap
 * (a single node with no same-machine provider yet): that's a transient, possibly-still-arriving
 * state a caller retries through, not an operational failure.
 */
public class GimleClusterException extends RuntimeException {

  private GimleClusterException(String message) {
    super(message);
  }

  public static GimleClusterException noReachableSeed(String nodeId, List<String> seeds) {
    return new GimleClusterException(
        "node " + nodeId + " could not reach any of its configured seed members: " + seeds);
  }

  /**
   * {@code interfaceName} only, not a full {@code ServiceExport}: {@code
   * ServiceRegistry#lookup(Class)} never carries a version to look up by, so a cross-tier fabric
   * lookup genuinely has no version to name here either.
   */
  public static GimleClusterException noExportingMember(String interfaceName) {
    return new GimleClusterException("no member anywhere in the cluster exports " + interfaceName);
  }
}
