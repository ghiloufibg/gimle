package com.gimle.mimir.raft;

/** A voter's response to a {@link RequestVote}. */
public record RequestVoteResponse(long term, boolean voteGranted) implements RaftRpc {}
