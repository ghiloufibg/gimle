package com.example.orderfulfillment.shipping;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. Its own
 *  simulated carrier rejections are a business-logic condition, not a process health condition. */
public final class ShippingServiceLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
