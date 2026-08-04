package com.gimle.mimir.store;

import com.gimle.core.protocol.NodeHeartbeat;
import java.time.Instant;

/**
 * A node heartbeat as stored: the report itself, plus when the control plane received it -- the
 * timestamp {@link NodeHeartbeat} itself doesn't carry, needed to detect a node gone dark.
 */
public record ObservedHeartbeat(NodeHeartbeat heartbeat, Instant receivedAt) {

  public ObservedHeartbeat {
    if (heartbeat == null) {
      throw new IllegalArgumentException("heartbeat must not be null");
    }
    if (receivedAt == null) {
      throw new IllegalArgumentException("receivedAt must not be null");
    }
  }
}
