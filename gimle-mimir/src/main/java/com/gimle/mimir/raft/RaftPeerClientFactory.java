package com.gimle.mimir.raft;

/**
 * Builds a {@link RaftPeerClient} for a peer {@link RaftNode} has just learned about -- either
 * because this node itself proposed adding it ({@link RaftNode#addServer}) or because it appeared
 * in a {@link MembershipChange} entry replicated from the current leader. The production
 * implementation dials a real socket ({@code address -> new PeerConnection(...)}, wired by {@code
 * StoreMain}); a node constructed via {@link RaftNode}'s legacy fixed-peer-map constructor (every
 * test that never exercises live membership changes) never invokes this at all.
 */
@FunctionalInterface
public interface RaftPeerClientFactory {

  RaftPeerClient connect(PeerAddress address);
}
