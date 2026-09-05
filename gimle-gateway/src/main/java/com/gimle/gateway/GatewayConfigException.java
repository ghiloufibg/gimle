package com.gimle.gateway;

/**
 * A malformed {@code gateway.tlsCertificates} configuration value -- rejected at parse time, before
 * any HTTP listener ever binds, rather than discovered lazily on a route's first request.
 */
public final class GatewayConfigException extends RuntimeException {

  public GatewayConfigException(String message) {
    super(message);
  }
}
