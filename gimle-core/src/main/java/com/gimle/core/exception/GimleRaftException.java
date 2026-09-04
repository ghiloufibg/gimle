package com.gimle.core.exception;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * A Raft-replicated control plane's consensus failures: a write rejected by a non-leader, a
 * proposal that never commits, a read whose leader could not prove it still leads, or a
 * follower/restarting node that can't trust its own persisted snapshot. Reads throw here rather
 * than quietly answering from a replica that may be behind -- refusing a read the cluster cannot
 * currently stand behind is the honest outcome, and every caller treats it as retryable.
 */
public class GimleRaftException extends RuntimeException {

  private GimleRaftException(String message) {
    super(message);
  }

  private GimleRaftException(String message, Throwable cause) {
    super(message, cause);
  }

  public static GimleRaftException notLeader(String nodeId, Optional<String> knownLeaderAddress) {
    return new GimleRaftException(
        "node "
            + nodeId
            + " is not the Raft leader"
            + knownLeaderAddress
                .map(address -> "; known leader: " + address)
                .orElse("; leader unknown"));
  }

  public static GimleRaftException proposalTimedOut(String nodeId, Duration waited) {
    return new GimleRaftException("node " + nodeId + "'s proposal did not commit within " + waited);
  }

  /**
   * A leader could not establish a read index within {@code waited}: it never confirmed with a
   * majority of its voting peers that it is still the leader, so it has no way to know whether a
   * newer leader has committed writes it has not seen. Distinct from {@link #notLeader}, which
   * names a node that already knows it isn't leading -- this one is a node that still believes it
   * leads but cannot prove it, and so refuses to answer rather than serve a read that may be
   * missing another leader's committed writes.
   */
  public static GimleRaftException readIndexTimedOut(String nodeId, Duration waited) {
    return new GimleRaftException(
        "node "
            + nodeId
            + " could not confirm its leadership with a majority within "
            + waited
            + "; refusing to serve a possibly stale read");
  }

  public static GimleRaftException snapshotCorrupted(Path snapshotFile, Throwable cause) {
    return new GimleRaftException("corrupt Raft snapshot at " + snapshotFile, cause);
  }

  /**
   * The client-side counterpart to {@link #notLeader}: a {@code StoreClient} exhausted every
   * configured store endpoint -- including one leader-follow retry against a hinted address --
   * without a successful response, for a leader-only operation ({@code propose}, a heartbeat, or a
   * lease call).
   */
  public static GimleRaftException storeUnreachable(String operation) {
    return new GimleRaftException(
        "no reachable store leader could serve " + operation + " after retrying every endpoint");
  }

  /**
   * The etcd-style membership-change safety rule this codebase ships in place of full joint
   * consensus: a leader rejects a new {@code AddServer}/{@code RemoveServer} while an earlier one
   * it proposed is still uncommitted, rather than allowing two configurations to ever be in flight
   * at once.
   */
  public static GimleRaftException membershipChangeInFlight(String nodeId) {
    return new GimleRaftException(
        "node " + nodeId + " already has an uncommitted membership change in flight");
  }

  public static GimleRaftException alreadyAMember(String nodeId, String peerId) {
    return new GimleRaftException(
        "node " + nodeId + " cannot add " + peerId + ": already a cluster member");
  }

  public static GimleRaftException notAMember(String nodeId, String peerId) {
    return new GimleRaftException(
        "node " + nodeId + " cannot remove " + peerId + ": not a cluster member");
  }

  /**
   * The client-side counterpart to a {@code StoreNode}-answered {@code MutationRejected} for {@code
   * addServer}/{@code removeServer}: the answering node genuinely was leader and evaluated the
   * request, but rejected it for a real, deterministic reason (already a member, not a member, or
   * another change still in flight) -- retrying against a *different* endpoint would just reach the
   * same leader (or a follower that redirects back to it) and reject identically, unlike a genuine
   * not-leader redirect, which retrying elsewhere actually resolves. {@code reason} is the leader's
   * own rejection message, carried verbatim rather than re-derived, so the caller sees exactly what
   * the leader evaluated rather than a generic "could not reach a leader" that hides it.
   */
  public static GimleRaftException membershipChangeRejected(String reason) {
    return new GimleRaftException(reason);
  }
}
