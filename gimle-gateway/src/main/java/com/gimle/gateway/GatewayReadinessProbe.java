package com.gimle.gateway;

import com.gimle.module.probe.ReadinessProbe;

/**
 * Ready once {@link GatewayHooks#onStart} has actually bound its HTTP port -- not ready to front
 * external traffic before there's anywhere to send it, matching {@code greeter-load-generator}'s
 * own readiness probe.
 */
public final class GatewayReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return GatewayHooks.ready.get();
  }
}
