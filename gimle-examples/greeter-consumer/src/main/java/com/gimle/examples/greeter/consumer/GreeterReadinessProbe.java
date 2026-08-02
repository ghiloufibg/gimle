package com.gimle.examples.greeter.consumer;

import com.gimle.module.probe.ReadinessProbe;

/**
 * Ready only once {@link GreeterConsumerHooks} has actually reached greeter-provider at least once
 * -- a consumer isn't really ready to serve traffic until its own dependency is reachable.
 */
public final class GreeterReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return GreeterConsumerHooks.everSucceeded.get();
  }
}
