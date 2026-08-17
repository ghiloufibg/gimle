package com.gimle.examples.greeter.loadgen;

/**
 * A missing or malformed {@code load.port} configuration value -- rejected before any HTTP listener
 * ever binds, the same posture {@code gimle-gateway}'s own {@code GatewayConfigException} takes for
 * its own config-driven port.
 */
public final class LoadGeneratorConfigException extends RuntimeException {

  public LoadGeneratorConfigException(String message) {
    super(message);
  }
}
