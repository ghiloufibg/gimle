package com.example.orderfulfillment.shipping;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link ShippingServiceHooks#onStart} has registered the service. */
public final class ShippingServiceReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return ShippingServiceHooks.ready.get();
  }
}
