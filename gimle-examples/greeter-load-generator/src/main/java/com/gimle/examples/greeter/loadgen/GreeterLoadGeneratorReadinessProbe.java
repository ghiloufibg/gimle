package com.gimle.examples.greeter.loadgen;

import com.gimle.module.probe.ReadinessProbe;

/**
 * Ready once {@link GreeterLoadGeneratorHooks#onStart} has actually bound its HTTP port -- not
 * ready to serve traffic before an external load generator has anywhere to send it.
 */
public final class GreeterLoadGeneratorReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return GreeterLoadGeneratorHooks.ready.get();
  }
}
