package com.gimle.controlplane.raft;

/** A voter's response to a {@link RequestVote} (design §2.2). */
public record RequestVoteResponse(long term, boolean voteGranted) implements RaftRpc {}
