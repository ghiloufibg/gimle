package com.gimle.examples.greeter.provider;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link GreeterProviderHooks#onStart} has actually registered the service. */
public final class GreeterReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return GreeterProviderHooks.ready.get();
  }
}
