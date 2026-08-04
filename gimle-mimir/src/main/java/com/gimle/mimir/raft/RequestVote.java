package com.gimle.mimir.raft;

/** A candidate's bid for votes in {@code term}. */
public record RequestVote(long term, String candidateId, long lastLogIndex, long lastLogTerm)
    implements RaftRpc {}
