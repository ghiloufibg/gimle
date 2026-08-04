package com.gimle.mimir.raft;

/** A follower's response to {@link InstallSnapshot}. */
public record InstallSnapshotResponse(long term) implements RaftRpc {}
