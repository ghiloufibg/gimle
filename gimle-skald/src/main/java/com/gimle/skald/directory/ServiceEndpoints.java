package com.gimle.skald.directory;

import java.util.List;

/**
 * The decoded shape of {@code GET /services/{name}/endpoints}: {@code name}/{@code port}/{@code
 * targetPort} are carried through for completeness even though Skald itself only ever needs {@code
 * endpointHosts} -- a DNS A-record answer is address-only, no port.
 */
public record ServiceEndpoints(String name, int port, int targetPort, List<String> endpointHosts) {

  public ServiceEndpoints {
    endpointHosts = List.copyOf(endpointHosts);
  }
}
