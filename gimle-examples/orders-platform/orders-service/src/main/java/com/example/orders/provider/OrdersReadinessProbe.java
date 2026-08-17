package com.example.orders.provider;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once {@link OrdersServiceHooks#onStart} has published {@link OrderBook} on the fabric --
 * shares that static flag with the hooks class the same way the platform's own greeter-provider
 * example does. */
public final class OrdersReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return OrdersServiceHooks.ready.get();
  }
}
