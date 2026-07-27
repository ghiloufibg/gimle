package com.gimle.controlplane.raft;

/**
 * A follower's response to {@link AppendEntries} (design §2.2/§2.3): {@code matchIndex} is only
 * meaningful when {@code success} is {@code true} -- the leader's replication loop only reads it in
 * that case, per the one-index-at-a-time backtrack this design uses on failure.
 */
public record AppendEntriesResponse(long term, boolean success, long matchIndex)
    implements RaftRpc {}
