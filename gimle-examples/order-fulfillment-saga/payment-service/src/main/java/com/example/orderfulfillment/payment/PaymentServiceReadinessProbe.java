package com.example.orderfulfillment.payment;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link PaymentServiceHooks#onStart} has registered the service. */
public final class PaymentServiceReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return PaymentServiceHooks.ready.get();
  }
}
