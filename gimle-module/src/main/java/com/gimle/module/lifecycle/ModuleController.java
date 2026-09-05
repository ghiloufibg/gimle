package com.gimle.module.lifecycle;

import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.module.layer.ModuleLayerFactory;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.resolve.ModuleWiring;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a module through {@code INSTALLED -> RESOLVED -> STARTING -> ACTIVE -> STOPPING ->
 * UNINSTALLED} (plus {@code FAILED}), invoking its lifecycle hooks and building its {@link
 * ModuleLayer} at the right points. One 1:1 hook-per-verb mapping: {@code onInstall} fires once the
 * layer is built (end of {@code resolve}), {@code onStart}/{@code onStop} bracket {@code ACTIVE},
 * {@code onUninstall} fires just before disposal. Gating hooks ({@code onInstall}, {@code onStart})
 * abort their transition and propagate synchronously on failure; teardown hooks ({@code onStop},
 * {@code onUninstall}) are best-effort — a misbehaving hook is recorded in a {@code
 * TransitionFailed} event but never blocks resource disposal.
 *
 * <p>Doesn't itself touch the shared {@link ServiceRegistry} beyond handing each module's {@link
 * ModuleContext} a reference to it — marking a stopping module's services not-ready and removing
 * them on uninstall is {@code gimle-worker}'s {@code WorkerRuntime}'s job, reacting to the same
 * {@link LifecycleEvent} stream it already consumes for scheduler/probe management, not this
 * class's.
 */
public final class ModuleController {

  private static final Logger log = LoggerFactory.getLogger(ModuleController.class);

  /** The default {@code onDisposed} for a caller that doesn't care about layer disposal. */
  private static final BiConsumer<ModuleInstanceId, ModuleLayerHandle> NO_OP_ON_DISPOSED =
      (id, handle) -> {};

  /**
   * The default {@code relay} for a caller that doesn't wire the real agent-backed collaborator
   * (every pre-existing constructor below, and any test constructing a controller directly) --
   * matches {@link SimpleModuleContext}'s own default posture for the same collaborator.
   */
  private static final ControlPlaneRelayClient NO_OP_RELAY = ControlPlaneRelayClient.unavailable();

  /**
   * The default {@code identityLookup} for a caller that doesn't wire the real registry-backed
   * collaborator -- an empty answer, matching {@link ModuleContext#instanceInfo}'s documented
   * "identity not known here" case rather than failing.
   */
  private static final Function<ModuleInstanceId, Optional<ModuleContext.InstanceInfo>>
      NO_OP_IDENTITY = id -> Optional.empty();

  private final ModuleRegistry registry;
  private final ModuleResolver resolver;
  private final ModuleLayer platformLayer;
  private final ClassLoader parentLoader;
  private final Duration drainTimeout;
  private final Consumer<LifecycleEvent> eventSink;
  private final BiConsumer<ModuleInstanceId, ModuleLayerHandle> onDisposed;
  private final ServiceRegistry serviceRegistry;
  private final ControlPlaneRelayClient relay;
  private final Function<ModuleInstanceId, Optional<ModuleContext.InstanceInfo>> identityLookup;

  private final Map<ModuleInstanceId, ModuleLifecycleHooks> hooksByModule =
      new ConcurrentHashMap<>();
  private final Map<ModuleInstanceId, SimpleModuleContext> contextsByModule =
      new ConcurrentHashMap<>();

  /**
   * Shared across every {@link SimpleModuleContext} this controller creates -- a config/secret
   * value delivered via {@link #deliverConfig} before or after a given module resolves both work
   * identically, since every context reads through to this same live map rather than a snapshot
   * taken at construction time.
   */
  private final Map<String, String> configValues = new ConcurrentHashMap<>();

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink) {
    this(
        registry,
        resolver,
        platformLayer,
        parentLoader,
        drainTimeout,
        eventSink,
        NO_OP_ON_DISPOSED,
        new SimpleServiceRegistry());
  }

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleInstanceId, ModuleLayerHandle> onDisposed) {
    this(
        registry,
        resolver,
        platformLayer,
        parentLoader,
        drainTimeout,
        eventSink,
        onDisposed,
        new SimpleServiceRegistry());
  }

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      ServiceRegistry serviceRegistry) {
    this(
        registry,
        resolver,
        platformLayer,
        parentLoader,
        drainTimeout,
        eventSink,
        NO_OP_ON_DISPOSED,
        serviceRegistry);
  }

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleInstanceId, ModuleLayerHandle> onDisposed,
      ServiceRegistry serviceRegistry) {
    this(
        registry,
        resolver,
        platformLayer,
        parentLoader,
        drainTimeout,
        eventSink,
        onDisposed,
        serviceRegistry,
        NO_OP_RELAY);
  }

  /**
   * {@code relay} is what {@code gimle-worker}'s {@code WorkerMain} supplies to let a resolved
   * module's {@link ModuleContext} reach back into the control plane over the worker-agent control
   * channel (see {@link ModuleContext#relayControlPlaneRead}) -- every other constructor above
   * defaults it to {@link #NO_OP_RELAY}, matching this class's existing back-compat pattern for
   * {@code onDisposed}/{@code serviceRegistry}.
   */
  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleInstanceId, ModuleLayerHandle> onDisposed,
      ServiceRegistry serviceRegistry,
      ControlPlaneRelayClient relay) {
    this(
        registry,
        resolver,
        platformLayer,
        parentLoader,
        drainTimeout,
        eventSink,
        onDisposed,
        serviceRegistry,
        relay,
        NO_OP_IDENTITY);
  }

  /**
   * The full constructor: every collaborator explicit, no defaulting. {@code identityLookup} is
   * what {@code WorkerMain} supplies to answer {@link ModuleContext#instanceInfo} from its own live
   * instance-identity registry -- looked up per call, never snapshotted, since an instance's
   * identity can be registered (or re-registered, on an in-place rename) after its context already
   * exists.
   */
  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleInstanceId, ModuleLayerHandle> onDisposed,
      ServiceRegistry serviceRegistry,
      ControlPlaneRelayClient relay,
      Function<ModuleInstanceId, Optional<ModuleContext.InstanceInfo>> identityLookup) {
    this.registry = registry;
    this.resolver = resolver;
    this.platformLayer = platformLayer;
    this.parentLoader = parentLoader;
    this.drainTimeout = drainTimeout;
    this.eventSink = eventSink;
    this.onDisposed = onDisposed;
    this.serviceRegistry = serviceRegistry;
    this.relay = relay;
    this.identityLookup = identityLookup;
  }

  /**
   * Called by {@code gimle-worker}'s {@code WorkerMain} on {@code ControlMessage.ConfigDelivered}:
   * makes {@code value} visible via {@code ModuleContext.config(key)} for every module this
   * controller hosts, whether it resolved before or after this call. Notifies every hosted module's
   * own {@code ModuleContext.onConfigChange} listeners only when the value actually changed, so a
   * relay that re-sends an unchanged value doesn't wake a listener for nothing.
   */
  public void deliverConfig(String key, String value) {
    String previous = configValues.put(key, value);
    if (!Objects.equals(previous, value)) {
      notifyConfigChange(new ModuleContext.ConfigChange(key, Optional.of(value)));
    }
  }

  /**
   * Called by {@code gimle-worker}'s {@code WorkerMain} on {@code
   * ControlMessage.ConfigKeysRetained}: drops every locally-held config/secret key not named in
   * {@code keys}, which is the full set that still exists upstream for the instances this worker
   * hosts. This is how a key deleted from a ConfigMap or Secret stops being readable by a running
   * instance instead of surviving until its next restart.
   *
   * <p>Applying a whole-set assertion rather than reacting to per-key removals is what makes this
   * converge from any starting state: this controller does not need to have seen the deletion, or
   * any prior tick, for the very next assertion to leave it holding exactly the right keys.
   */
  public void retainConfigKeys(Collection<String> keys) {
    Set<String> retained = Set.copyOf(keys);
    for (String key : List.copyOf(configValues.keySet())) {
      if (!retained.contains(key) && configValues.remove(key) != null) {
        notifyConfigChange(new ModuleContext.ConfigChange(key, Optional.empty()));
      }
    }
  }

  private void notifyConfigChange(ModuleContext.ConfigChange change) {
    for (SimpleModuleContext context : contextsByModule.values()) {
      context.notifyConfigChange(change);
    }
  }

  public ModuleWiring resolve(ModuleInstanceId id) {
    return resolve(id, Map.of());
  }

  /**
   * {@code dataDirectories} maps each of this instance's declared volume names to its
   * persistent-volume host path, already resolved by the agent and delivered over {@code
   * ControlMessage.ResolveModule} -- non-empty only for a {@code StatefulSet}-shaped instance whose
   * descriptor declares {@code volumes:}. Populated on {@link
   * SimpleModuleContext#dataDirectory(String)} before {@code onInstall} fires below, so a hook can
   * rely on it from its very first callback.
   */
  public ModuleWiring resolve(ModuleInstanceId id, Map<String, Path> dataDirectories) {
    requireState(id, ModuleState.INSTALLED, ModuleState.RESOLVED);

    ModuleWiring wiring;
    try {
      wiring = resolver.resolve(id);
    } catch (RuntimeException e) {
      markFailedAndEmit(id, ModuleState.INSTALLED, ModuleState.RESOLVED, e);
      throw e;
    }

    List<ModuleLayer> parentLayers = new ArrayList<>();
    parentLayers.add(platformLayer);
    for (ModuleInstanceId depId : new LinkedHashSet<>(wiring.wiredDependencies().values())) {
      ModuleLayerHandle depHandle =
          registry
              .layerHandle(depId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "dependency " + depId + " is resolved/active but has no layer handle"));
      parentLayers.add(depHandle.layer());
    }

    ModuleArtifact artifact = registry.artifact(id);
    ModuleLayerHandle handle;
    try {
      handle = ModuleLayerFactory.create(id, artifact.jarPath(), parentLayers, parentLoader);
    } catch (RuntimeException e) {
      markFailedAndEmit(id, ModuleState.INSTALLED, ModuleState.RESOLVED, e);
      throw e;
    }

    registry.markResolved(id, wiring, handle);
    emit(new LifecycleEvent.Resolved(id, wiring, Instant.now()));

    SimpleModuleContext ctx =
        new SimpleModuleContext(
            id,
            serviceRegistry,
            configValues,
            dataDirectories,
            relay,
            () -> identityLookup.apply(id));
    contextsByModule.put(id, ctx);
    try {
      Optional<ModuleLifecycleHooks> hooks = instantiateHooks(id, handle);
      hooks.ifPresent(h -> hooksByModule.put(id, h));
      if (hooks.isPresent()) {
        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(handle.loader());
        try {
          hooks.get().onInstall(ctx);
        } finally {
          Thread.currentThread().setContextClassLoader(previousCl);
        }
      }
    } catch (RuntimeException e) {
      contextsByModule.remove(id);
      hooksByModule.remove(id);
      GimleLifecycleException wrapped =
          e instanceof GimleLifecycleException gle
              ? gle
              : GimleLifecycleException.hookFailed(id, "onInstall", e);
      markFailedAndEmit(id, ModuleState.INSTALLED, ModuleState.RESOLVED, wrapped);
      throw wrapped;
    }

    return wiring;
  }

  public void start(ModuleInstanceId id) {
    requireState(id, ModuleState.RESOLVED, ModuleState.STARTING);
    registry.markStarting(id);
    emit(new LifecycleEvent.Starting(id, Instant.now()));

    ModuleContext ctx = contextsByModule.get(id);
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.get(id));
    if (hooks.isPresent()) {
      try {
        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleLoader =
            registry.layerHandle(id).map(ModuleLayerHandle::loader).orElse(previousCl);
        Thread.currentThread().setContextClassLoader(moduleLoader);
        try {
          hooks.get().onStart(ctx);
        } finally {
          Thread.currentThread().setContextClassLoader(previousCl);
        }
      } catch (RuntimeException e) {
        GimleLifecycleException wrapped = GimleLifecycleException.hookFailed(id, "onStart", e);
        markFailedAndEmit(id, ModuleState.RESOLVED, ModuleState.STARTING, wrapped);
        throw wrapped;
      }
    }

    registry.markActive(id);
    emit(new LifecycleEvent.Active(id, Instant.now()));
  }

  /**
   * ACTIVE -&gt; STOPPING -&gt; UNINSTALLED in one call: drains, then disposes regardless of
   * outcome.
   */
  public void stop(ModuleInstanceId id) {
    requireState(id, ModuleState.ACTIVE, ModuleState.STOPPING);
    Instant deadline = Instant.now().plus(drainTimeout);
    registry.markStopping(id);
    emit(new LifecycleEvent.Stopping(id, deadline, Instant.now()));

    ModuleContext ctx = contextsByModule.get(id);
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.get(id));
    if (hooks.isPresent()) {
      try {
        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleLoader =
            registry.layerHandle(id).map(ModuleLayerHandle::loader).orElse(previousCl);
        Thread.currentThread().setContextClassLoader(moduleLoader);
        try {
          hooks.get().onStop(ctx);
        } finally {
          Thread.currentThread().setContextClassLoader(previousCl);
        }
      } catch (RuntimeException e) {
        emit(
            new LifecycleEvent.TransitionFailed(
                id,
                ModuleState.ACTIVE,
                ModuleState.STOPPING,
                GimleLifecycleException.hookFailed(id, "onStop", e),
                Instant.now()));
      }
    }

    awaitDrain(ctx, deadline);
    finishUninstall(id);
  }

  /** FAILED (or any pre-ACTIVE state) -&gt; UNINSTALLED, with no drain wait. */
  public void uninstall(ModuleInstanceId id) {
    ModuleState current = registry.state(id);
    if (current == ModuleState.ACTIVE) {
      throw GimleLifecycleException.illegalTransition(
          id, current.name(), ModuleState.UNINSTALLED.name());
    }
    finishUninstall(id);
  }

  private void finishUninstall(ModuleInstanceId id) {
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.remove(id));
    ModuleContext ctx = contextsByModule.remove(id);
    if (hooks.isPresent()) {
      try {
        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        ClassLoader moduleLoader =
            registry.layerHandle(id).map(ModuleLayerHandle::loader).orElse(previousCl);
        Thread.currentThread().setContextClassLoader(moduleLoader);
        try {
          hooks.get().onUninstall(ctx);
        } finally {
          Thread.currentThread().setContextClassLoader(previousCl);
        }
      } catch (RuntimeException e) {
        emit(
            new LifecycleEvent.TransitionFailed(
                id,
                registry.state(id),
                ModuleState.UNINSTALLED,
                GimleLifecycleException.hookFailed(id, "onUninstall", e),
                Instant.now()));
      }
    }

    Optional<ModuleLayerHandle> handle = registry.layerHandle(id);
    registry.remove(id);
    emit(new LifecycleEvent.Uninstalled(id, Instant.now()));
    handle.ifPresent(h -> onDisposed.accept(id, h));
  }

  private void awaitDrain(ModuleContext ctx, Instant deadline) {
    while (ctx.inFlightCount() > 0 && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(Duration.ofMillis(10));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private Optional<ModuleLifecycleHooks> instantiateHooks(
      ModuleInstanceId id, ModuleLayerHandle handle) {
    Optional<String> hooksClassName = registry.artifact(id).descriptor().lifecycleHooksClass();
    if (hooksClassName.isEmpty()) {
      return Optional.empty();
    }
    String className = hooksClassName.get();
    try {
      Class<?> hooksClass = Class.forName(className, true, handle.loader());
      Object instance = hooksClass.getDeclaredConstructor().newInstance();
      if (!(instance instanceof ModuleLifecycleHooks hooks)) {
        throw GimleLifecycleException.hookFailed(
            id,
            "instantiate",
            new ClassCastException(className + " does not implement ModuleLifecycleHooks"));
      }
      return Optional.of(hooks);
    } catch (ReflectiveOperationException e) {
      throw GimleLifecycleException.hookFailed(id, "instantiate:" + className, e);
    }
  }

  /**
   * The {@link ModuleContext} this controller created for {@code id} at resolve time, if it's still
   * resolved/active -- lets a caller outside this class (e.g. {@code FabricServer}, dispatching an
   * inbound fabric call) route work through the exact same {@code beginRequest}/{@code endRequest}
   * in-flight counter {@link #stop}'s drain wait already reads, rather than that counter only ever
   * being incremented by a hosted module's own hook code and never by real external traffic.
   */
  public Optional<ModuleContext> context(ModuleInstanceId id) {
    return Optional.ofNullable(contextsByModule.get(id));
  }

  /**
   * Forces an {@code ACTIVE} module straight to {@code FAILED}, for a caller that has already
   * decided restarting further is pointless (e.g. {@code WorkerRuntime} exhausting a module's
   * restart budget) -- unlike {@link #markFailedAndEmit}'s other call sites, there's no thrown hook
   * exception driving this, just a policy decision, so {@code cause} is synthesized from {@code
   * reason}. Going through this method (rather than {@code registry.markFailed} directly) is what
   * makes the failure visible: it emits the same {@link LifecycleEvent.TransitionFailed} {@code
   * WorkerMain}'s lifecycle sink already turns into a {@code ControlMessage
   * .ModuleStateChanged("FAILED")}, which is what flips {@code AgentMain}'s {@code alive} flag and
   * lets {@code HealthReconciler}'s machine-tier reschedule fire -- the escalation this method
   * exists to unblock.
   */
  public void forceFailed(ModuleInstanceId id, String reason) {
    requireState(id, ModuleState.ACTIVE, ModuleState.FAILED);
    markFailedAndEmit(
        id, ModuleState.ACTIVE, ModuleState.FAILED, new IllegalStateException(reason));
  }

  /**
   * Reports an instance as failed when it is past the point {@link #forceFailed} can help: a
   * restart that already drove the module through {@code UNINSTALLED} removed it from the registry
   * entirely, so there is no state left to mark, and requiring {@code ACTIVE} would leave the
   * instance stranded with nothing anywhere recording that it is dead.
   *
   * <p>Emits the same {@link LifecycleEvent.TransitionFailed} the registry-backed path emits -- the
   * event, not the registry write, is what reaches the agent as {@code
   * ControlMessage.ModuleStateChanged("FAILED")} and lets the machine-tier reschedule fire. Marks
   * the registry too when the module is still known, so an instance that never got uninstalled ends
   * in the same terminal state either way.
   */
  public void abandonFailed(ModuleInstanceId id, String reason) {
    ModuleState from;
    try {
      from = registry.state(id);
      registry.markFailed(id);
    } catch (RuntimeException e) {
      // Already gone from the registry -- the event below is the only record left to make.
      from = ModuleState.UNINSTALLED;
    }
    emit(
        new LifecycleEvent.TransitionFailed(
            id, from, ModuleState.FAILED, new IllegalStateException(reason), Instant.now()));
  }

  /**
   * The run-to-completion counterpart to {@link #stop}: a Job-kind module's {@link JobHooks#run}
   * finished, reporting {@code status}. {@code SUCCEEDED} moves the module straight to {@link
   * ModuleState#COMPLETED} -- no drain wait, unlike {@link #stop}'s
   * ACTIVE-&gt;STOPPING-&gt;UNINSTALLED sequence, since a Job never serves external requests and
   * its {@code inFlightCount()} is always zero. {@code FAILED} reuses the existing {@link
   * #markFailedAndEmit} path rather than introducing a second failure terminal -- a Job run that
   * reported failure is handled identically to any other hook failure from here on (worker
   * heartbeat reports {@code alive=false}, {@code HealthReconciler} escalates). Neither branch
   * tears down this module's hook/context entries or its worker JVM -- that's driven externally, by
   * the agent reacting to this instance's assignment disappearing once the control plane observes
   * the terminal state, the same "assignment gone -&gt; agent stops it" mechanism ordinary
   * scale-down already relies on.
   */
  public void complete(ModuleInstanceId id, CompletionStatus status) {
    if (status == CompletionStatus.SUCCEEDED) {
      requireState(id, ModuleState.ACTIVE, ModuleState.COMPLETED);
      registry.markCompleted(id);
      emit(new LifecycleEvent.Completed(id, Instant.now()));
    } else {
      requireState(id, ModuleState.ACTIVE, ModuleState.FAILED);
      markFailedAndEmit(
          id,
          ModuleState.ACTIVE,
          ModuleState.FAILED,
          new IllegalStateException("job run reported FAILED"));
    }
  }

  private void requireState(ModuleInstanceId id, ModuleState expected, ModuleState attemptingTo) {
    ModuleState current = registry.state(id);
    if (current != expected) {
      throw GimleLifecycleException.illegalTransition(id, current.name(), attemptingTo.name());
    }
  }

  private void markFailedAndEmit(
      ModuleInstanceId id, ModuleState from, ModuleState to, Throwable cause) {
    registry.markFailed(id);
    emit(new LifecycleEvent.TransitionFailed(id, from, to, cause, Instant.now()));
  }

  /**
   * Logs every transition (previously silently dropped even from this worker's own log -- a {@code
   * TransitionFailed} cause was visible nowhere until it reached whatever consumed {@link
   * #eventSink}, if anything did) before forwarding to {@code eventSink}, the same order every
   * other side-effecting call in this class follows: durable/local effect first, notification
   * after.
   */
  private void emit(LifecycleEvent event) {
    if (event instanceof LifecycleEvent.TransitionFailed failed) {
      log.warn(
          "module {} failed transitioning {} -> {}",
          failed.id(),
          failed.from(),
          failed.to(),
          failed.cause());
    } else {
      log.info("module {} {}", event.id(), event.getClass().getSimpleName());
    }
    try {
      eventSink.accept(event);
    } catch (RuntimeException e) {
      log.error("module {} event sink threw handling {}", event.id(), event, e);
    }
  }
}
