package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link ModuleContext}: an atomic in-flight counter, and a thin delegate onto a shared
 * {@link ServiceRegistry}.
 */
public final class SimpleModuleContext implements ModuleContext {

  private final ModuleId id;
  private final ServiceRegistry serviceRegistry;
  private final AtomicInteger inFlight = new AtomicInteger();

  public SimpleModuleContext(ModuleId id, ServiceRegistry serviceRegistry) {
    this.id = id;
    this.serviceRegistry = serviceRegistry;
  }

  @Override
  public int inFlightCount() {
    return inFlight.get();
  }

  @Override
  public void beginRequest() {
    inFlight.incrementAndGet();
  }

  @Override
  public void endRequest() {
    inFlight.updateAndGet(n -> Math.max(0, n - 1));
  }

  @Override
  public <T> void registerService(Class<T> iface, T instance) {
    serviceRegistry.register(id, iface, instance);
  }

  @Override
  public <T> Optional<T> lookupService(Class<T> iface) {
    return serviceRegistry.lookup(iface);
  }
}
