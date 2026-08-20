package com.example.sessionstore.store;

import com.gimle.module.probe.ReadinessProbe;

/**
 * Ready only once {@link SessionStoreHooks#onStart} has replayed the volume's log and registered
 * the service -- both must happen before this instance can honestly answer a {@code get}.
 */
public final class SessionStoreReadinessProbe implements ReadinessProbe {

  @Override
  public boolean isReady() {
    return SessionStoreHooks.ready.get();
  }
}
