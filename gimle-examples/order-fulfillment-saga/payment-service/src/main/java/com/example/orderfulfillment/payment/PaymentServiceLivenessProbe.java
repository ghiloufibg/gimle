package com.example.orderfulfillment.payment;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. Its own
 *  simulated card declines are a business-logic condition, not a process health condition. */
public final class PaymentServiceLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
