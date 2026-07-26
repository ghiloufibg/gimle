package com.gimle.worker.testsupport;

import com.gimle.module.probe.ReadinessProbe;
import java.util.concurrent.atomic.AtomicBoolean;

/** See {@code ControllableLivenessProbe} for why this needs to be shared static state. */
public final class ControllableReadinessProbe implements ReadinessProbe {

  public static final AtomicBoolean READY = new AtomicBoolean(true);

  @Override
  public boolean isReady() {
    return READY.get();
  }
}
