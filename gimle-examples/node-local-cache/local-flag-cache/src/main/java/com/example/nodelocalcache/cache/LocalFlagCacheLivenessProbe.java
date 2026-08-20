package com.example.nodelocalcache.cache;

import com.gimle.module.probe.LivenessProbe;

/** This module has no failure mode of its own to report -- always alive once loaded. */
public final class LocalFlagCacheLivenessProbe implements LivenessProbe {

  @Override
  public boolean isAlive() {
    return true;
  }
}
