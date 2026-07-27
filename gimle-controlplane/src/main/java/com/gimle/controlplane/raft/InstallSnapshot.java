package com.gimle.controlplane.raft;

/**
 * Sent by the leader instead of {@link AppendEntries} when a follower's {@code nextIndex} falls at
 * or below the leader's own log-compaction floor. {@code snapshotBytes} is a {@link
 * RaftCodec}-encoded {@code com.gimle.controlplane.store.StateSnapshot}.
 */
public record InstallSnapshot(
    long term, String leaderId, long lastIncludedIndex, long lastIncludedTerm, byte[] snapshotBytes)
    implements RaftRpc {

  public InstallSnapshot {
    if (snapshotBytes == null) {
      throw new IllegalArgumentException("snapshotBytes must not be null");
    }
    snapshotBytes = snapshotBytes.clone();
  }

  @Override
  public byte[] snapshotBytes() {
    return snapshotBytes.clone();
  }
}
