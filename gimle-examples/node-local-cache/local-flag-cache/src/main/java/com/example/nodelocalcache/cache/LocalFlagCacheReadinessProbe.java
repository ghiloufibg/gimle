package com.example.nodelocalcache.cache;

import com.gimle.module.probe.ReadinessProbe;

/** Ready only once {@link LocalFlagCacheHooks#onStart} has actually registered the service. */
public final class LocalFlagCacheReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return LocalFlagCacheHooks.ready.get();
  }
}
