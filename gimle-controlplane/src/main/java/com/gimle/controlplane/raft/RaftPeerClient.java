package com.gimle.controlplane.raft;

/**
 * The outbound side of the Raft RPC surface: what {@link RaftNode} uses to reach one specific peer.
 * {@link PeerConnection} is the real, socket-backed implementation; tests implement this directly
 * against another in-process {@link RaftNode}'s {@link RaftRpcHandler} methods, with no networking
 * at all, to exercise consensus logic in isolation. Every method is expected to throw an unchecked
 * exception (never a peer-specific checked one) on failure -- an unreachable peer for one RPC
 * cycle, not a special case the caller must catch differently per method.
 */
public interface RaftPeerClient {

  RequestVoteResponse requestVote(RequestVote request);

  AppendEntriesResponse appendEntries(AppendEntries request);

  InstallSnapshotResponse installSnapshot(InstallSnapshot request);
}
