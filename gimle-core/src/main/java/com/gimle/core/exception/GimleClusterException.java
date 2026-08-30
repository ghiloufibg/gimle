package com.gimle.core.exception;

/**
 * A cluster-level operational failure with no per-request retry left to try: a fabric service
 * lookup exhausting every endpoint the membership table knows about for an interface, cluster-wide,
 * or a cluster-internal HTTP call answering with a non-success status -- the cluster-scoped analog
 * of {@link GimleSchedulingException}'s "no feasible placement." Never thrown for a merely local
 * gap (a single node with no same-machine provider yet, or a gossip member whose seeds are all
 * momentarily unreachable at join time -- see {@code GossipMember#join}/{@code
 * #retrySeedsIfIsolated}): those are transient, possibly-still-arriving states a caller retries
 * through, not an operational failure.
 */
public class GimleClusterException extends RuntimeException {

  private GimleClusterException(String message) {
    super(message);
  }

  /**
   * {@code interfaceName} only, not a full {@code ServiceExport}: {@code
   * ServiceRegistry#lookup(Class)} never carries a version to look up by, so a cross-tier fabric
   * lookup genuinely has no version to name here either.
   */
  public static GimleClusterException noExportingMember(String interfaceName) {
    return new GimleClusterException("no member anywhere in the cluster exports " + interfaceName);
  }

  /**
   * A cluster-internal HTTP call answered with a non-success status -- surfaced with the status and
   * body verbatim, so a denial reads as the denial it is rather than as a JSON parse failure over
   * its plain-text error body.
   */
  public static GimleClusterException unexpectedHttpStatus(
      String operation, int status, String body) {
    return new GimleClusterException(operation + " returned " + status + ": " + body);
  }
}
