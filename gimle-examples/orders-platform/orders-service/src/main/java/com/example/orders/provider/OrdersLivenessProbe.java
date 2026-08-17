package com.example.orders.provider;

import com.gimle.module.probe.LivenessProbe;

/** No failure mode of its own -- there's nothing this demo app does that can wedge the process
 * without the JVM itself going down, which the worker would already notice another way. */
public final class OrdersLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
