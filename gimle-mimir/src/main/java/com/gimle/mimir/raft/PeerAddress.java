package com.gimle.mimir.raft;

/**
 * A peer's full network identity: the host and Raft peer-to-peer port {@link PeerConnection} dials
 * to reach it, plus the client-facing port a {@code StoreTransport} listens on there -- exactly the
 * three pieces of information {@code StoreMain}'s own {@code --peers host:raftPort:clientPort} flag
 * already parses per peer, promoted here so a {@link MembershipChange} log entry can carry
 * everything a receiving {@link RaftNode} (to dial the peer's Raft port) and a {@code StoreNode}
 * (to answer a client redirect with the peer's client port) need, with nothing propagated out of
 * band.
 */
public record PeerAddress(String host, int raftPort, int clientPort) {

  public PeerAddress {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
  }

  /**
   * The Raft peer ID this address is reachable at -- {@code host:raftPort}, matching every existing
   * raft-ID convention in this codebase (e.g. {@code StoreMain}'s own {@code selfRaftId}).
   */
  public String raftId() {
    return host + ":" + raftPort;
  }

  public String clientAddress() {
    return host + ":" + clientPort;
  }
}
