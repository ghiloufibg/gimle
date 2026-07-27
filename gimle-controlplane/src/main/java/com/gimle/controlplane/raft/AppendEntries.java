package com.gimle.controlplane.raft;

import java.util.List;

/**
 * Sent by the leader to a follower, both as a heartbeat (empty {@code entries}) and as log
 * replication (design §2.2).
 */
public record AppendEntries(
    long term,
    String leaderId,
    long prevLogIndex,
    long prevLogTerm,
    List<LogEntry> entries,
    long leaderCommitIndex)
    implements RaftRpc {

  public AppendEntries {
    entries = List.copyOf(entries);
  }
}
