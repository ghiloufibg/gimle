package com.gimle.agent.bifrost;

import java.util.List;
import java.util.OptionalInt;

/**
 * The control plane's view of one service: its stable virtual port (what {@link BifrostProxy} binds
 * its loopback listener on), the port its endpoints were required to listen on (empty when the
 * Service declares none and each endpoint's own reported port stands instead), and the currently
 * live endpoint set to forward to. Mirrors the {@code GET /services/{name}/endpoints} response body
 * verbatim, so {@link HttpServiceSource} can build one straight off the parsed JSON.
 *
 * <p>{@code udp} is what decides which kind of listener {@link BifrostProxy} binds for this Service
 * -- a datagram relay rather than a stream one. Carried as a boolean rather than the manifest's own
 * enum because {@code gimle-agent} has no dependency on {@code gimle-mimir}'s manifest types, the
 * same reason {@code NetworkPolicyRule} exists separately from {@code NetworkPolicySpec}.
 */
public record ServiceEndpoints(
    String name,
    int port,
    OptionalInt targetPort,
    boolean sessionAffinity,
    List<ServiceEndpoint> endpoints,
    boolean udp) {

  /** Convenience: a TCP endpoint set with no session affinity declared. */
  public ServiceEndpoints(
      String name, int port, OptionalInt targetPort, List<ServiceEndpoint> endpoints) {
    this(name, port, targetPort, false, endpoints, false);
  }

  /** Convenience: a TCP endpoint set. */
  public ServiceEndpoints(
      String name,
      int port,
      OptionalInt targetPort,
      boolean sessionAffinity,
      List<ServiceEndpoint> endpoints) {
    this(name, port, targetPort, sessionAffinity, endpoints, false);
  }
}
