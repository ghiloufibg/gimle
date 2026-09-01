package com.gimle.controlplane.service;

import java.util.List;

/**
 * What {@link ServiceEndpointResolver#resolve} found for one Service this tick: the live endpoints
 * to route to, plus one human-readable line per backing instance that was deliberately left out
 * because no port on it could be chosen. The exclusions exist so a Service whose {@code targetPort}
 * matches nothing an instance reports -- or whose instances report several ports with no {@code
 * targetPort} naming one -- shows up as a stated reason in the log rather than as a silently short
 * endpoint list an operator can only diagnose by opening a socket.
 *
 * <p>An instance simply not ready, or on a node with no registered address, is not an exclusion:
 * those are ordinary transient states every reconcile already expects, not a misconfiguration.
 */
public record ServiceEndpointResolution(List<ServiceEndpoint> endpoints, List<String> exclusions) {

  public ServiceEndpointResolution {
    endpoints = List.copyOf(endpoints);
    exclusions = List.copyOf(exclusions);
  }
}
