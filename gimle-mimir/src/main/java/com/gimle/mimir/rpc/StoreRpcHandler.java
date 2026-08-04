package com.gimle.mimir.rpc;

/**
 * The inbound side of the client-facing store RPC surface -- {@code StoreNode} implements this;
 * {@code StoreTransport} depends only on this interface, never on {@code StoreNode} directly, the
 * same decoupling {@link com.gimle.mimir.raft.RaftTransport} already keeps from {@code RaftNode}
 * via {@link com.gimle.mimir.raft.RaftRpcHandler}. One dispatch method rather than {@code
 * RaftRpcHandler}'s one-method-per-RPC-kind shape: Raft's three RPC kinds are algorithmically
 * distinct (election, replication, snapshot install), but {@code StoreRpc}'s ~20 request variants
 * differ only in which {@code StateStore} field they touch, not in how they're handled -- a single
 * {@code switch} inside {@code StoreNode} fits that shape better than twenty near-identical methods
 * here.
 */
public interface StoreRpcHandler {

  StoreRpc.Response handle(StoreRpc.Request request);
}
