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
}
