package com.example.nodelocalcache.consumer;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once at least one call to FeatureFlagCache has succeeded. */
public final class FlagConsumerReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return FlagConsumerHooks.everSucceeded.get();
  }
}
