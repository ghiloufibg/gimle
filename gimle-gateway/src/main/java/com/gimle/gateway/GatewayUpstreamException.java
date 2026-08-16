package com.gimle.gateway;

/**
 * A vessel route's endpoint list could not be resolved at all -- no cached list to fall back on,
 * and the fresh relay attempt either failed (a non-2xx {@code RelayResult}) or came back with a
 * body {@link VesselEndpointCache} couldn't parse. Carries the HTTP status {@link
 * GatewayDispatcher} should report to the external caller, mirroring {@link
 * GatewayBadRequestException}'s own "carry the response status on the exception itself" shape.
 * Caught inside {@link VesselEndpointCache} itself -- never crosses that class's own public API.
 */
final class GatewayUpstreamException extends RuntimeException {

  private final int status;

  GatewayUpstreamException(int status, String message) {
    super(message);
    this.status = status;
  }

  int status() {
    return status;
  }
}
