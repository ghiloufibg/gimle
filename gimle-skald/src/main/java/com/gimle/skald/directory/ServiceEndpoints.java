package com.gimle.skald.directory;

import java.util.List;

/**
 * The decoded shape of {@code GET /services/{name}/endpoints}: {@code port}/{@code targetPort} are
 * the Service's own declared pair, {@code endpoints} each endpoint's actual {@code host:port} --
 * per endpoint, since an {@code SRV} answer names each endpoint's own port, not the Service-level
 * declaration.
 */
public record ServiceEndpoints(String name, int port, int targetPort, List<HostPort> endpoints) {

  public ServiceEndpoints {
    endpoints = List.copyOf(endpoints);
  }
}
