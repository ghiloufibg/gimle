package com.gimle.controlplane.raft;

/**
 * A follower's response to {@link AppendEntries}. {@code matchIndex} is only meaningful when {@code
 * success} is {@code true} -- the leader's replication loop only reads it in that case, since a
 * failure instead triggers a one-index-at-a-time backtrack.
 */
public record AppendEntriesResponse(long term, boolean success, long matchIndex)
    implements RaftRpc {}
