package com.example.frauddetection.alertsink;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link AlertSinkHooks#onStart} has actually registered the service. */
public final class AlertSinkReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return AlertSinkHooks.ready.get();
  }
}
