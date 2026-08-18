package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Default {@link ModuleContext}: an atomic in-flight counter, a thin delegate onto a shared {@link
 * ServiceRegistry}, and a live view onto a shared {@code configValues} map. The map is shared
 * across every context {@link ModuleController} creates for one worker, not copied per instance --
 * config delivered by the agent before or after a given module resolves both work identically,
 * since every context reads through to the same live map rather than a snapshot taken at
 * construction time.
 */
public final class SimpleModuleContext implements ModuleContext {

  /**
   * The default {@code relay} for a caller that doesn't wire the real agent-backed collaborator
   * (every pre-existing constructor below, and any test building a context directly) -- a
   * consistent, synthesized "not available" result rather than a {@code NullPointerException} the
   * first time a module calls {@link #relayControlPlaneRead}.
   */
  private static final Function<String, RelayResult> NO_OP_RELAY =
      path -> new RelayResult(501, "control-plane relay is not available on this context");

  private final ModuleId id;
  private final ServiceRegistry serviceRegistry;
  private final Map<String, String> configValues;
  private final Optional<Path> dataDirectory;
  private final Function<String, RelayResult> relay;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Map<String, Integer> reportedPorts = new ConcurrentHashMap<>();

  public SimpleModuleContext(ModuleId id, ServiceRegistry serviceRegistry) {
    this(id, serviceRegistry, new ConcurrentHashMap<>());
  }

  public SimpleModuleContext(
      ModuleId id, ServiceRegistry serviceRegistry, Map<String, String> configValues) {
    this(id, serviceRegistry, configValues, Optional.empty());
  }

  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Optional<Path> dataDirectory) {
    this(id, serviceRegistry, configValues, dataDirectory, NO_OP_RELAY);
  }

  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Optional<Path> dataDirectory,
      Function<String, RelayResult> relay) {
    this.id = id;
    this.serviceRegistry = serviceRegistry;
    this.configValues = configValues;
    this.dataDirectory = dataDirectory;
    this.relay = relay;
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
  public Optional<Object> invokeServiceByName(
      String interfaceName,
      int majorVersion,
      String methodName,
      String[] paramTypeNames,
      Object[] args)
      throws Throwable {
    return serviceRegistry.invokeByName(
        interfaceName, majorVersion, methodName, paramTypeNames, args);
  }

  @Override
  public Optional<String> config(String key) {
    return Optional.ofNullable(configValues.get(key));
  }

  @Override
  public Optional<Path> dataDirectory() {
    return dataDirectory;
  }

  @Override
  public RelayResult relayControlPlaneRead(String path) {
    return relay.apply(path);
  }

  @Override
  public void reportPort(String name, int port) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("reported port name must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("reported port out of range: " + port);
    }
    reportedPorts.put(name, port);
  }

  @Override
  public Map<String, Integer> reportedPorts() {
    return Map.copyOf(reportedPorts);
  }
}
