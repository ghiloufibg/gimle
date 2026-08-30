package com.gimle.examples.greeting.operator;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link GreetingOperatorHooks#onStart} has actually started its loop. */
public final class GreetingOperatorReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return GreetingOperatorHooks.running.get();
  }
}
