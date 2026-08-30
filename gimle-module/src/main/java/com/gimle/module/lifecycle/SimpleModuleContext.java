package com.gimle.module.lifecycle;

import com.gimle.core.module.ModuleId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

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
   * first time a module calls {@link #relayControlPlaneRead}/{@link #reportResourceStatus}.
   */
  private static final ControlPlaneRelayClient NO_OP_RELAY = ControlPlaneRelayClient.unavailable();

  /**
   * The default {@code instanceInfo} for a caller that doesn't wire the real registry-backed
   * collaborator -- the documented "identity not known here" answer, matching {@link
   * #NO_OP_RELAY}'s posture for the same situation.
   */
  private static final Supplier<Optional<InstanceInfo>> NO_OP_INSTANCE_INFO = Optional::empty;

  private final ModuleId id;
  private final ServiceRegistry serviceRegistry;
  private final Map<String, String> configValues;
  private final Map<String, Path> dataDirectories;
  private final ControlPlaneRelayClient relay;
  private final Supplier<Optional<InstanceInfo>> instanceInfo;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Map<String, Integer> reportedPorts = new ConcurrentHashMap<>();

  public SimpleModuleContext(ModuleId id, ServiceRegistry serviceRegistry) {
    this(id, serviceRegistry, new ConcurrentHashMap<>());
  }

  public SimpleModuleContext(
      ModuleId id, ServiceRegistry serviceRegistry, Map<String, String> configValues) {
    this(id, serviceRegistry, configValues, Map.<String, Path>of());
  }

  /** Convenience: a sole volume (named {@code data}) or none -- the single-volume test shape. */
  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Optional<Path> dataDirectory) {
    this(id, serviceRegistry, configValues, soleVolume(dataDirectory), NO_OP_RELAY);
  }

  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Map<String, Path> dataDirectories) {
    this(id, serviceRegistry, configValues, dataDirectories, NO_OP_RELAY);
  }

  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Map<String, Path> dataDirectories,
      ControlPlaneRelayClient relay) {
    this(id, serviceRegistry, configValues, dataDirectories, relay, NO_OP_INSTANCE_INFO);
  }

  /**
   * Read-only relay convenience for a caller (typically a test) that scripts only {@link
   * #relayControlPlaneRead} -- status reporting answers the same synthesized "not available" result
   * the no-op relay gives everything.
   */
  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Map<String, Path> dataDirectories,
      Function<String, RelayResult> readOnlyRelay) {
    this(id, serviceRegistry, configValues, dataDirectories, readOnly(readOnlyRelay));
  }

  /** Adapts a read-only relay function onto the two-operation client shape. */
  public static ControlPlaneRelayClient readOnly(Function<String, RelayResult> read) {
    return new ControlPlaneRelayClient() {
      @Override
      public RelayResult read(String path) {
        return read.apply(path);
      }

      @Override
      public RelayResult putResourceStatus(
          String kindName, Optional<String> tenantId, String name, String statusJson) {
        return new RelayResult(501, "status reporting is not available on this context");
      }
    };
  }

  /**
   * The full constructor: {@code instanceInfo} is read live on every {@link #instanceInfo()} call,
   * never snapshotted, since an instance's identity can be registered (or re-registered on an
   * in-place rename) after this context already exists.
   */
  public SimpleModuleContext(
      ModuleId id,
      ServiceRegistry serviceRegistry,
      Map<String, String> configValues,
      Map<String, Path> dataDirectories,
      ControlPlaneRelayClient relay,
      Supplier<Optional<InstanceInfo>> instanceInfo) {
    this.id = id;
    this.serviceRegistry = serviceRegistry;
    this.configValues = configValues;
    this.dataDirectories = Map.copyOf(dataDirectories);
    this.relay = relay;
    this.instanceInfo = instanceInfo;
  }

  private static Map<String, Path> soleVolume(Optional<Path> dataDirectory) {
    return dataDirectory.map(path -> Map.of("data", path)).orElse(Map.of());
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
  public Set<String> configKeys() {
    return Set.copyOf(configValues.keySet());
  }

  @Override
  public Optional<InstanceInfo> instanceInfo() {
    return instanceInfo.get();
  }

  @Override
  public Optional<Path> dataDirectory() {
    if (dataDirectories.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(dataDirectories.values().iterator().next());
  }

  @Override
  public Optional<Path> dataDirectory(String name) {
    return Optional.ofNullable(dataDirectories.get(name));
  }

  @Override
  public RelayResult relayControlPlaneRead(String path) {
    return relay.read(path);
  }

  @Override
  public RelayResult reportResourceStatus(
      String kindName, Optional<String> tenantId, String name, String statusJson) {
    return relay.putResourceStatus(kindName, tenantId, name, statusJson);
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
