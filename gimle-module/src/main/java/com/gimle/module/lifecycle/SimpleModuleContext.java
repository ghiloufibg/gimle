package com.gimle.module.lifecycle;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ModuleContext}: an atomic in-flight counter, a thin delegate onto a shared {@link
 * ServiceRegistry}, and a live view onto a shared {@code configValues} map. The map is shared
 * across every context {@link ModuleController} creates for one worker, not copied per instance --
 * config delivered by the agent before or after a given module resolves both work identically,
 * since every context reads through to the same live map rather than a snapshot taken at
 * construction time. Change listeners are the one piece deliberately not shared: they are held per
 * context so they die with the instance that registered them.
 */
public final class SimpleModuleContext implements ModuleContext {

  private static final Logger log = LoggerFactory.getLogger(SimpleModuleContext.class);

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

  // Held per context, never on the shared config map, so an uninstalled instance's listeners --
  // and the module classloader they close over -- go away with the context itself rather than
  // pinning a disposed ModuleLayer's loader alive in a worker-wide registry.
  private final List<Consumer<ConfigChange>> configListeners = new CopyOnWriteArrayList<>();

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
  public ConfigSubscription onConfigChange(final Consumer<ConfigChange> listener) {
    if (listener == null) {
      throw new IllegalArgumentException("config change listener must not be null");
    }
    configListeners.add(listener);
    return () -> configListeners.remove(listener);
  }

  /**
   * Fans {@code change} out to this context's own registered listeners. Called by {@link
   * ModuleController} once it has already applied the change to the shared config map, so a
   * listener that reads {@link #config} from its callback sees the new state, not the old one. A
   * listener that throws is contained here: the remaining listeners still run, and the delivery
   * that triggered this is unaffected.
   */
  void notifyConfigChange(final ConfigChange change) {
    // Tagged here, not by whoever delivered the change: a listener runs on whatever thread the
    // delivery arrived on, which carries no instance identity at all, so anything the module logged
    // from its own callback used to be categorized as this worker's PLATFORM output rather than
    // this instance's own APPLICATION log -- the one place an operator looks for it, and the only
    // hook whose logging behaved differently from every other.
    final Map<String, String> tags = instanceMdcTags();
    for (Consumer<ConfigChange> listener : configListeners) {
      try {
        InstanceMdcContext.runTagged(
            tags,
            () -> {
              listener.accept(change);
              return null;
            });
      } catch (Exception e) {
        log.warn(
            "config change listener for {} failed on key {}: {}", id, change.key(), e.getMessage());
      }
    }
  }

  /**
   * This instance's own logging tags, or empty for a context with no instance identity registered
   * (a plain unit-test context, or a module installed before its identity landed) -- the same
   * "degrade, don't fail" posture every other identity read here takes.
   */
  private Map<String, String> instanceMdcTags() {
    return instanceInfo
        .get()
        .map(
            info ->
                InstanceMdcContext.tagsFor(
                    info.deploymentName(),
                    info.instanceIndex(),
                    id.name(),
                    id.version().toString(),
                    info.tenantId().orElse(null)))
        .orElse(Map.of());
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
