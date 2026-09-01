package com.gimle.agent.bifrost;

import java.util.List;
import java.util.OptionalInt;

/**
 * The control plane's view of one service: its stable virtual port (what {@link BifrostProxy} binds
 * its loopback listener on), the port its endpoints were required to listen on (empty when the
 * Service declares none and each endpoint's own reported port stands instead), and the currently
 * live endpoint set to forward to. Mirrors the {@code GET /services/{name}/endpoints} response body
 * verbatim, so {@link HttpServiceSource} can build one straight off the parsed JSON.
 */
public record ServiceEndpoints(
    String name,
    int port,
    OptionalInt targetPort,
    boolean sessionAffinity,
    List<ServiceEndpoint> endpoints) {

  /** Convenience: an endpoint set with no session affinity declared. */
  public ServiceEndpoints(
      String name, int port, OptionalInt targetPort, List<ServiceEndpoint> endpoints) {
    this(name, port, targetPort, false, endpoints);
  }
}
