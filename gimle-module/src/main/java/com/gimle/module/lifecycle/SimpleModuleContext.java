package com.gimle.module.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;

/** Default {@link ModuleContext}: an atomic in-flight counter the drain wait reads directly. */
public final class SimpleModuleContext implements ModuleContext {

  private final AtomicInteger inFlight = new AtomicInteger();

  @Override
  public int in_flight_count() {
    return inFlight.get();
  }

  @Override
  public void begin_request() {
    inFlight.incrementAndGet();
  }

  @Override
  public void end_request() {
    inFlight.updateAndGet(n -> Math.max(0, n - 1));
  }
}
