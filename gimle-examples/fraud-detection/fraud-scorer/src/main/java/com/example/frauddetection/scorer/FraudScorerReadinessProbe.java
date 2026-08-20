package com.example.frauddetection.scorer;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link FraudScorerHooks#onStart} has actually registered the service. */
public final class FraudScorerReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return FraudScorerHooks.ready.get();
  }
}
