package com.example.sessionstore.client;

import com.gimle.module.probe.ReadinessProbe;

/** Ready once at least one full write/recall cycle against SessionStore has succeeded. */
public final class SessionStoreClientReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return SessionStoreClientHooks.everSucceeded.get();
  }
}
