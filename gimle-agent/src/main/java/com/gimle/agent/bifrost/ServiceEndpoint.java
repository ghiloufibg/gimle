package com.gimle.agent.bifrost;

import java.util.Optional;

/**
 * One live backend a service's traffic can be forwarded to: a reachable host and port, plus the
 * cluster node it runs on when the control plane knows one -- what lets {@link ServiceListener}
 * prefer endpoints local to its own node. Empty {@code nodeId} means "on no cluster node" (an
 * ExternalName Service's external host) or an older endpoints payload with no node attribution;
 * either way the endpoint simply never counts as local.
 */
public record ServiceEndpoint(String host, int port, Optional<String> nodeId) {

  public ServiceEndpoint {
    if (nodeId == null) {
      throw new IllegalArgumentException("nodeId must be Optional.empty(), not null");
    }
  }

  /** Convenience: an endpoint with no node attribution. */
  public ServiceEndpoint(String host, int port) {
    this(host, port, Optional.empty());
  }

  /** The same endpoint attributed to {@code nodeId}. */
  public ServiceEndpoint withNodeId(String attributedNodeId) {
    return new ServiceEndpoint(host, port, Optional.of(attributedNodeId));
  }
}
