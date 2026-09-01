package com.gimle.skald.directory;

import java.util.List;
import java.util.OptionalInt;

/**
 * The decoded shape of {@code GET /services/{name}/endpoints}: {@code port} is what callers dial
 * and {@code targetPort} what the Service required its backends to listen on (empty when it
 * declared none), {@code endpoints} each endpoint's actual {@code host:port} -- per endpoint, since
 * an {@code SRV} answer names each endpoint's own port, not the Service-level declaration.
 */
public record ServiceEndpoints(
    String name, int port, OptionalInt targetPort, List<HostPort> endpoints) {

  public ServiceEndpoints {
    endpoints = List.copyOf(endpoints);
  }
}
