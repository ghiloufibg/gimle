package com.gimle.mimir.raft;

/**
 * Sent by the leader instead of {@link AppendEntries} when a follower's {@code nextIndex} falls at
 * or below the leader's own log-compaction floor. Chunked per the Raft paper's Figure 13 ({@code
 * offset}/{@code done}), not one RPC carrying the whole snapshot: {@code data} is one chunk of a
 * {@link RaftCodec}-encoded {@code com.gimle.mimir.store.StateSnapshot}, {@code offset} is this
 * chunk's byte position within that encoded snapshot, and {@code done} marks the final chunk. A
 * multi-hundred-MB snapshot sent as a single RPC would otherwise have to fit under {@link
 * RaftCodec}'s own {@code MAX_FRAME_LENGTH} and would hold the peer connection hostage for the
 * whole transfer with no way to observe progress or recover short of restarting from byte 0.
 */
public record InstallSnapshot(
    long term,
    String leaderId,
    long lastIncludedIndex,
    long lastIncludedTerm,
    long offset,
    byte[] data,
    boolean done)
    implements RaftRpc {

  public InstallSnapshot {
    if (data == null) {
      throw new IllegalArgumentException("data must not be null");
    }
    data = data.clone();
  }

  @Override
  public byte[] data() {
    return data.clone();
  }
}
