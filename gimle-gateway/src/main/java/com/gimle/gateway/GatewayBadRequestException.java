package com.gimle.gateway;

/**
 * An inbound HTTP request's body doesn't match its route's declared {@link GatewayRoute.ParamType}
 * -- {@link GatewayDispatcher} maps this to a 400 response, never a bare 500 for what is a caller
 * error, not a gateway or downstream-service failure.
 */
public final class GatewayBadRequestException extends RuntimeException {

  public GatewayBadRequestException(String message) {
    super(message);
  }
}
