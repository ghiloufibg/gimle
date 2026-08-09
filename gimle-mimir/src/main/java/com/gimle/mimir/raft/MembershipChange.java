package com.gimle.mimir.raft;

import java.util.Map;

/**
 * A complete replacement of this cluster's peer configuration (excluding whichever node applies it
 * -- a node is always implicitly a member of its own cluster and never appears in its own peer map,
 * matching every existing {@code Map<String, RaftPeerClient> peers} convention {@link RaftNode}
 * already had before this type existed), not a delta. Etcd-style: only one {@link MembershipChange}
 * is ever in flight at a time (enforced by {@link RaftNode#addServer}/{@link RaftNode#removeServer}
 * rejecting a new one while an earlier one they proposed is still uncommitted), each one entirely
 * replacing {@code C_old} with {@code C_new} -- deliberately not full joint consensus's {@code
 * C_old,new} overlap state, which the production-hardening backlog explicitly scopes out of this
 * item.
 */
public record MembershipChange(Map<String, PeerAddress> peers) implements RaftLogPayload {

  public MembershipChange {
    peers = Map.copyOf(peers);
  }
}
