package com.gimle.fabric.cluster;

import java.net.InetSocketAddress;

/**
 * A cluster member's identity: its node id and the UDP address its {@link GossipMember} listens on.
 */
public record MemberId(String nodeId, InetSocketAddress gossipAddress) {

  public MemberId {
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("nodeId must not be blank");
    }
    if (gossipAddress == null) {
      throw new IllegalArgumentException("gossipAddress must not be null");
    }
  }
}
