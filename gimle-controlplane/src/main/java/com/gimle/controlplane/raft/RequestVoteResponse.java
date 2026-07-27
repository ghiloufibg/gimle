package com.gimle.controlplane.raft;

/** A voter's response to a {@link RequestVote}. */
public record RequestVoteResponse(long term, boolean voteGranted) implements RaftRpc {}
