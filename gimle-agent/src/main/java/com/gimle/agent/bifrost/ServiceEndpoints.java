package com.gimle.agent.bifrost;

import java.util.List;

/**
 * The control plane's view of one service: its stable virtual port (what {@link BifrostProxy} binds
 * its loopback listener on), the port its endpoints actually listen on, and the currently live
 * endpoint set to forward to. Mirrors the {@code GET /services/{name}/endpoints} response body
 * verbatim, so {@link HttpServiceSource} can build one straight off the parsed JSON.
 */
public record ServiceEndpoints(
    String name, int port, int targetPort, List<ServiceEndpoint> endpoints) {}
