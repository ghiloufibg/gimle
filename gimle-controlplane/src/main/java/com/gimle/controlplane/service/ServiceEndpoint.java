package com.gimle.controlplane.service;

/**
 * One live, dialable address behind a {@link com.gimle.mimir.manifest.ServiceSpec}: a backing
 * instance's node host paired with the real port that instance is actually listening on --
 * everything a caller needs to open a socket, and nothing more (unlike {@code GET
 * /endpoints/{name}}'s richer per-instance shape, which also carries {@code nodeId}/{@code
 * instanceIndex} for operator-facing display).
 */
public record ServiceEndpoint(String host, int port) {

  public ServiceEndpoint {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be in [1, 65535], got " + port);
    }
  }
}
