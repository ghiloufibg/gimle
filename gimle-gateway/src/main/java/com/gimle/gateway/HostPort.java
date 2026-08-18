package com.gimle.gateway;

/**
 * A resolved, dialable target for an outbound gateway proxy call -- shared by every route kind that
 * resolves its target through a polled, cached endpoint list ({@link VesselEndpointCache}, {@link
 * ServiceEndpointCache}) before handing it to {@link VesselProxyClient}.
 */
record HostPort(String host, int port) {}
