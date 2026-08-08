package com.gimle.module.lifecycle;

import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.module.layer.ModuleLayerFactory;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.module.resolve.ModuleWiring;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

  private final ModuleRegistry registry;
  private final ModuleResolver resolver;
  private final ModuleLayer platformLayer;
  private final ClassLoader parentLoader;
  private final Duration drainTimeout;
  private final Consumer<LifecycleEvent> eventSink;
  private final BiConsumer<ModuleId, ModuleLayerHandle> onDisposed;
  private final ServiceRegistry serviceRegistry;

  private final Map<ModuleId, ModuleLifecycleHooks> hooksByModule = new ConcurrentHashMap<>();
  private final Map<ModuleId, SimpleModuleContext> contextsByModule = new ConcurrentHashMap<>();

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
        (id, handle) -> {},
        new SimpleServiceRegistry());
  }

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleId, ModuleLayerHandle> onDisposed) {
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
        (id, handle) -> {},
        serviceRegistry);
  }

  public ModuleController(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platformLayer,
      ClassLoader parentLoader,
      Duration drainTimeout,
      Consumer<LifecycleEvent> eventSink,
      BiConsumer<ModuleId, ModuleLayerHandle> onDisposed,
      ServiceRegistry serviceRegistry) {
    this.registry = registry;
    this.resolver = resolver;
    this.platformLayer = platformLayer;
    this.parentLoader = parentLoader;
    this.drainTimeout = drainTimeout;
    this.eventSink = eventSink;
    this.onDisposed = onDisposed;
    this.serviceRegistry = serviceRegistry;
  }

  /**
   * Called by {@code gimle-worker}'s {@code WorkerMain} on {@code ControlMessage.ConfigDelivered}:
   * makes {@code value} visible via {@code ModuleContext.config(key)} for every module this
   * controller hosts, whether it resolved before or after this call.
   */
  public void deliverConfig(String key, String value) {
    configValues.put(key, value);
  }

  public ModuleWiring resolve(ModuleId id) {
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
    for (ModuleId depId : new LinkedHashSet<>(wiring.wiredDependencies().values())) {
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

    SimpleModuleContext ctx = new SimpleModuleContext(id, serviceRegistry, configValues);
    contextsByModule.put(id, ctx);
    try {
      Optional<ModuleLifecycleHooks> hooks = instantiateHooks(id, handle);
      hooks.ifPresent(h -> hooksByModule.put(id, h));
      if (hooks.isPresent()) {
        hooks.get().onInstall(ctx);
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

  public void start(ModuleId id) {
    requireState(id, ModuleState.RESOLVED, ModuleState.STARTING);
    registry.markStarting(id);
    emit(new LifecycleEvent.Starting(id, Instant.now()));

    ModuleContext ctx = contextsByModule.get(id);
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.get(id));
    if (hooks.isPresent()) {
      try {
        hooks.get().onStart(ctx);
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
  public void stop(ModuleId id) {
    requireState(id, ModuleState.ACTIVE, ModuleState.STOPPING);
    Instant deadline = Instant.now().plus(drainTimeout);
    registry.markStopping(id);
    emit(new LifecycleEvent.Stopping(id, deadline, Instant.now()));

    ModuleContext ctx = contextsByModule.get(id);
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.get(id));
    if (hooks.isPresent()) {
      try {
        hooks.get().onStop(ctx);
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
  public void uninstall(ModuleId id) {
    ModuleState current = registry.state(id);
    if (current == ModuleState.ACTIVE) {
      throw GimleLifecycleException.illegalTransition(
          id, current.name(), ModuleState.UNINSTALLED.name());
    }
    finishUninstall(id);
  }

  private void finishUninstall(ModuleId id) {
    Optional<ModuleLifecycleHooks> hooks = Optional.ofNullable(hooksByModule.remove(id));
    ModuleContext ctx = contextsByModule.remove(id);
    if (hooks.isPresent()) {
      try {
        hooks.get().onUninstall(ctx);
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

  private Optional<ModuleLifecycleHooks> instantiateHooks(ModuleId id, ModuleLayerHandle handle) {
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
  public void forceFailed(ModuleId id, String reason) {
    requireState(id, ModuleState.ACTIVE, ModuleState.FAILED);
    markFailedAndEmit(
        id, ModuleState.ACTIVE, ModuleState.FAILED, new IllegalStateException(reason));
  }

  private void requireState(ModuleId id, ModuleState expected, ModuleState attemptingTo) {
    ModuleState current = registry.state(id);
    if (current != expected) {
      throw GimleLifecycleException.illegalTransition(id, current.name(), attemptingTo.name());
    }
  }

  private void markFailedAndEmit(ModuleId id, ModuleState from, ModuleState to, Throwable cause) {
    registry.markFailed(id);
    emit(new LifecycleEvent.TransitionFailed(id, from, to, cause, Instant.now()));
  }

  private void emit(LifecycleEvent event) {
    eventSink.accept(event);
  }
}
