package com.example.frauddetection.alertsink;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. Its own
 *  simulated alert-delivery failures are a business-logic condition, not a process health
 *  condition -- deliberately never surfaced here. */
public final class AlertSinkLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
