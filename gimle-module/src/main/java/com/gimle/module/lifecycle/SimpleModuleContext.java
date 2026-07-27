package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link ModuleContext}: an atomic in-flight counter, a thin delegate onto a shared {@link
 * ServiceRegistry}, and a live view onto a shared {@code configValues} map. The map is shared
 * across every context {@link ModuleController} creates for one worker, not copied per instance --
 * config delivered by the agent before or after a given module resolves both work identically,
 * since every context reads through to the same live map rather than a snapshot taken at
 * construction time.
 */
public final class SimpleModuleContext implements ModuleContext {

  private final ModuleId id;
  private final ServiceRegistry serviceRegistry;
  private final Map<String, String> configValues;
  private final AtomicInteger inFlight = new AtomicInteger();

  public SimpleModuleContext(ModuleId id, ServiceRegistry serviceRegistry) {
    this(id, serviceRegistry, new ConcurrentHashMap<>());
  }

  public SimpleModuleContext(
      ModuleId id, ServiceRegistry serviceRegistry, Map<String, String> configValues) {
    this.id = id;
    this.serviceRegistry = serviceRegistry;
    this.configValues = configValues;
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

  @Override
  public Optional<String> config(String key) {
    return Optional.ofNullable(configValues.get(key));
  }
}
