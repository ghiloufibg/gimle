package com.example.sessionstore.client;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. */
public final class SessionStoreClientLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
