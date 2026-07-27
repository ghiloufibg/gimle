package com.gimle.controlplane.raft;

/**
 * One entry in a {@link RaftLog}: the term it was created in, its 1-based index, and the mutation
 * it carries.
 */
public record LogEntry(long term, long index, StateMutation mutation) {

  public LogEntry {
    if (term < 0) {
      throw new IllegalArgumentException("term must not be negative: " + term);
    }
    if (index < 1) {
      throw new IllegalArgumentException("index must be >= 1: " + index);
    }
    if (mutation == null) {
      throw new IllegalArgumentException("mutation must not be null");
    }
  }
}
