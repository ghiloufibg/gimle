package com.gimle.mimir.raft;

/**
 * One entry in a {@link RaftLog}: the term it was created in, its 1-based index, and the {@link
 * RaftLogPayload} it carries -- a {@link StateMutation} bound for {@code StateStore} once
 * committed, or a {@link MembershipChange} to this cluster's own peer set, applied on append.
 */
public record LogEntry(long term, long index, RaftLogPayload payload) {

  public LogEntry {
    if (term < 0) {
      throw new IllegalArgumentException("term must not be negative: " + term);
    }
    if (index < 1) {
      throw new IllegalArgumentException("index must be >= 1: " + index);
    }
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
  }
}
