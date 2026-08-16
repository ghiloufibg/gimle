package com.gimle.gateway;

import com.gimle.module.probe.LivenessProbe;

/**
 * This module has no failure mode of its own to report -- always alive once loaded, matching {@code
 * greeter-load-generator}'s own liveness probe.
 */
public final class GatewayLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
