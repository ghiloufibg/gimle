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

  @Override
  public <T> void register_service(Class<T> iface, T instance) {
    serviceRegistry.register(id, iface, instance);
  }

  @Override
  public <T> Optional<T> lookup_service(Class<T> iface) {
    return serviceRegistry.lookup(iface);
  }
}
