package com.gimle.controlplane.raft;

/** A candidate's bid for votes in {@code term} (design §2.2). */
public record RequestVote(long term, String candidateId, long lastLogIndex, long lastLogTerm)
    implements RaftRpc {}
