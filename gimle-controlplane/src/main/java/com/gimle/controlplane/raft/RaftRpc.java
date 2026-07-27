package com.gimle.controlplane.raft;

/**
 * Marker for every message that travels over {@link RaftTransport}, purely so {@link RaftCodec} has
 * one type to encode/decode against -- the direct analog of {@code gimle-fabric}'s {@code
 * FabricFrame} for fabric traffic.
 */
public sealed interface RaftRpc
    permits RequestVote,
        RequestVoteResponse,
        AppendEntries,
        AppendEntriesResponse,
        InstallSnapshot,
        InstallSnapshotResponse {}
