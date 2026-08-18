package com.example.ordersload;

import com.gimle.module.probe.ReadinessProbe;

/**
 * Ready once {@link OrdersLoadGeneratorHooks#onStart} has actually bound its HTTP port -- not
 * ready to serve traffic before a driving tool has anywhere to send it.
 */
public final class OrdersLoadGeneratorReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return OrdersLoadGeneratorHooks.ready.get();
  }
}
