package com.gimle.controlplane.service;

import java.util.Optional;

/**
 * One live, dialable address behind a {@link com.gimle.mimir.manifest.ServiceSpec}: a backing
 * instance's node host paired with the real port that instance is actually listening on --
 * everything a caller needs to open a socket -- plus the {@code nodeId} the instance runs on, so a
 * per-node proxy ({@code gimle-bifrost}) can prefer endpoints local to its own node without
 * re-deriving placement from the richer {@code GET /endpoints/{name}} shape. {@code nodeId} is
 * empty for an endpoint that lives on no cluster node at all -- an ExternalName Service's external
 * host.
 */
public record ServiceEndpoint(String host, int port, Optional<String> nodeId) {

  public ServiceEndpoint {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in [1, 65535], got " + port);
    }
    if (nodeId == null) {
      throw new IllegalArgumentException("nodeId must be Optional.empty(), not null");
    }
  }

  /** Convenience: an endpoint whose owning node is unknown or inapplicable. */
  public ServiceEndpoint(String host, int port) {
    this(host, port, Optional.empty());
  }
}
