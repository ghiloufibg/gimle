package com.gimle.mimir.raft;

/**
 * The inbound side of the Raft RPC surface -- {@link RaftNode} implements this; {@link
 * RaftTransport} depends only on this interface, never on {@code RaftNode} directly (the same
 * decoupling {@code gimle-fabric}'s {@code FabricServer} keeps from its {@code ServiceRegistry}).
 */
public interface RaftRpcHandler {

  RequestVoteResponse onRequestVote(RequestVote request);

  AppendEntriesResponse onAppendEntries(AppendEntries request);

  InstallSnapshotResponse onInstallSnapshot(InstallSnapshot request);
}
