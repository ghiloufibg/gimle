package com.gimle.core.exception;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * A Raft-replicated control plane's consensus failures: a write rejected by a non-leader, a
 * proposal that never commits, or a follower/restarting node that can't trust its own persisted
 * snapshot. Never thrown for ordinary follower-vs-leader staleness on reads -- that is normal,
 * expected behavior, not a failure.
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
