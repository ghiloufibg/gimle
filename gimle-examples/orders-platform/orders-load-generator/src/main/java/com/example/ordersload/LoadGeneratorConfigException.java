package com.example.ordersload;

/**
 * A missing or malformed {@code orders.load-port} configuration value -- rejected before any HTTP
 * listener ever binds, the same posture {@code greeter-load-generator}'s own
 * {@code LoadGeneratorConfigException} takes for its own config-driven port.
 */
public final class LoadGeneratorConfigException extends RuntimeException {

  public LoadGeneratorConfigException(String message) {
    super(message);
  }
}
