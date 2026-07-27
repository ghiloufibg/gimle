package com.gimle.controlplane.raft;

/** A follower's response to {@link InstallSnapshot} (design §2.4). */
public record InstallSnapshotResponse(long term) implements RaftRpc {}
