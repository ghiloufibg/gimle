package com.gimle.worker;

import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.probe.LivenessProbe;
import com.gimle.module.probe.ReadinessProbe;
import com.gimle.module.resolve.ModuleRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event-driven glue between Phase 1's {@link ModuleController} and Phase 2's worker-level
 * concerns: creates/disposes each module's {@link BoundedModuleScheduler} and probes in lockstep
 * with {@link LifecycleEvent}s, escalates repeated liveness failures to a module restart (and, once
 * that module's own restart budget is exhausted, to giving up on the whole worker), and owns the
 * {@code Stopping}-vs-{@code Uninstalled} service-registry teardown timing that {@link
 * ModuleController} deliberately leaves to its caller.
 *
 * <p>{@link #on_lifecycle_event} is exactly the {@code Consumer<LifecycleEvent>} {@link
 * ModuleController}'s constructor already accepts — no changes to {@code gimle-module} were needed
 * to wire this in, confirming that event sink was designed generally enough in Phase 1.
 */
public final class WorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

  private final ModuleController controller;
  private final ModuleRegistry registry;
  private final ServiceRegistry serviceRegistry;
  private final int defaultMaxConcurrency;
  private final Duration probeInterval;
  private final Duration probeTimeout;
  private final int livenessFailureThreshold;
  private final Consumer<ModuleId> onModuleRestartBudgetExhausted;

  private final Map<ModuleId, BoundedModuleScheduler> schedulers = new ConcurrentHashMap<>();
  private final Map<ModuleId, RestartTracker> restartTrackers = new ConcurrentHashMap<>();
  private final Map<ModuleId, AtomicInteger> consecutiveLivenessFailures =
      new ConcurrentHashMap<>();
  private final Set<ModuleId> restartsInFlight = ConcurrentHashMap.newKeySet();
  private final ProbeLoop probeLoop = new ProbeLoop();

  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration probeInterval,
      Duration probeTimeout,
      int livenessFailureThreshold,
      Consumer<ModuleId> onModuleRestartBudgetExhausted) {
    this.controller = controller;
    this.registry = registry;
    this.serviceRegistry = serviceRegistry;
    this.defaultMaxConcurrency = defaultMaxConcurrency;
    this.probeInterval = probeInterval;
    this.probeTimeout = probeTimeout;
    this.livenessFailureThreshold = livenessFailureThreshold;
    this.onModuleRestartBudgetExhausted = onModuleRestartBudgetExhausted;
  }

  public void on_lifecycle_event(LifecycleEvent event) {
    switch (event) {
      case LifecycleEvent.Active active -> on_active(active.id());
      case LifecycleEvent.Stopping stopping -> on_stopping(stopping.id());
      case LifecycleEvent.Uninstalled uninstalled -> on_uninstalled(uninstalled.id());
      default -> {}
    }
  }

  private void on_active(ModuleId id) {
    BoundedModuleScheduler scheduler = new BoundedModuleScheduler(id, defaultMaxConcurrency);
    schedulers.put(id, scheduler);
    restartTrackers.computeIfAbsent(id, key -> new_restart_tracker());
    consecutiveLivenessFailures.computeIfAbsent(id, key -> new AtomicInteger());

    ModuleDescriptor descriptor = registry.artifact(id).descriptor();
    Optional<ModuleLayerHandle> handleOpt = registry.layer_handle(id);
    if (handleOpt.isEmpty()) {
      log.warn("module {} is ACTIVE but has no layer handle; skipping probe setup", id);
      return;
    }
    ModuleLayerHandle handle = handleOpt.get();

    descriptor
        .healthProbes()
        .livenessClass()
        .ifPresent(
            className -> {
              LivenessProbe probe = instantiate(id, className, handle, LivenessProbe.class);
              probeLoop.start(
                  probe_key(id, "liveness"),
                  scheduler,
                  probe::is_alive,
                  probeInterval,
                  probeTimeout,
                  alive -> on_liveness_result(id, alive));
            });

    descriptor
        .healthProbes()
        .readinessClass()
        .ifPresent(
            className -> {
              ReadinessProbe probe = instantiate(id, className, handle, ReadinessProbe.class);
              probeLoop.start(
                  probe_key(id, "readiness"),
                  scheduler,
                  probe::is_ready,
                  probeInterval,
                  probeTimeout,
                  ready -> on_readiness_result(id, ready));
            });
  }

  private void on_stopping(ModuleId id) {
    probeLoop.stop(probe_key(id, "liveness"));
    probeLoop.stop(probe_key(id, "readiness"));
    serviceRegistry.mark_unready(id);
  }

  private void on_uninstalled(ModuleId id) {
    BoundedModuleScheduler scheduler = schedulers.remove(id);
    if (scheduler != null) {
      scheduler.close();
    }
    restartTrackers.remove(id);
    consecutiveLivenessFailures.remove(id);
    serviceRegistry.remove(id);
  }

  private void on_readiness_result(ModuleId id, boolean ready) {
    if (!ready) {
      serviceRegistry.mark_unready(id);
    }
    // Becoming ready again is re-established the next time the module registers a service
    // (on_start/on_install) or is naturally re-marked by a caller with fresher state; readiness
    // probes here only ever demote, matching the design's "readiness failures... just flip the
    // module's tracked readiness state" — there is deliberately no separate "mark ready" call
    // wired through this loop, since ServiceRegistry has no ambiguity to resolve by re-adding
    // an already-registered, already-ready entry.
  }

  private void on_liveness_result(ModuleId id, boolean alive) {
    if (alive) {
      consecutiveLivenessFailures.computeIfAbsent(id, key -> new AtomicInteger()).set(0);
      return;
    }
    int failures =
        consecutiveLivenessFailures
            .computeIfAbsent(id, key -> new AtomicInteger())
            .incrementAndGet();
    if (failures < livenessFailureThreshold) {
      return;
    }
    consecutiveLivenessFailures.get(id).set(0);
    restart_module(id);
  }

  private void restart_module(ModuleId id) {
    // The probe loop keeps ticking the (still ACTIVE-until-the-attempt-actually-runs) module the
    // whole time an attempt is in flight, so without this guard every subsequent liveness failure
    // during that window would trigger its own concurrent stop()/resolve()/start() sequence,
    // racing the first attempt and tripping over a module the first attempt already moved on
    // from. One restart in flight per module at a time.
    if (!restartsInFlight.add(id)) {
      return;
    }

    RestartTracker tracker = restartTrackers.computeIfAbsent(id, key -> new_restart_tracker());
    Instant now = Instant.now();
    if (!tracker.record_failure_and_check_should_retry(now)) {
      log.error("module {} exhausted its restart budget; giving up on this worker", id);
      restartsInFlight.remove(id);
      onModuleRestartBudgetExhausted.accept(id);
      return;
    }

    // Captured now, while the module still exists: ModuleController#stop() drains and fully
    // uninstalls (removes it from the registry entirely, per the UNINSTALLED -> [*] terminal),
    // so re-resolving the same id afterward would throw NoSuchElementException unless the
    // artifact is re-registered first. "Dispose and reinstantiate" genuinely means reinstall,
    // not just resolve+start on an id that's still sitting there.
    var artifact = registry.artifact(id);

    Duration delay = tracker.delay_until_next_attempt(now);
    Runnable attempt =
        () -> {
          try {
            try {
              Thread.sleep(delay);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            try {
              controller.stop(id);
              registry.register(artifact);
              controller.resolve(id);
              controller.start(id);
              tracker.record_success();
            } catch (RuntimeException e) {
              log.warn("module {} restart attempt failed: {}", id, e.getMessage());
            }
          } finally {
            restartsInFlight.remove(id);
          }
        };
    // Deliberately not run via this module's own BoundedModuleScheduler: controller.stop(id)
    // above synchronously reaches on_uninstalled(), which closes that very scheduler -- an
    // ExecutorService#close() call blocks awaiting termination of its own in-flight tasks, so
    // running the restart on the scheduler it's about to close would deadlock the restart against
    // itself. Restarting is worker-orchestration, not module request work, so it doesn't belong
    // on the module's own bounded concurrency budget anyway.
    Thread.ofVirtual().name("gimle-restart-" + id.name() + "-" + id.version()).start(attempt);
  }

  private static RestartTracker new_restart_tracker() {
    return new RestartTracker(
        Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 5, Duration.ofSeconds(60));
  }

  private static String probe_key(ModuleId id, String kind) {
    return id + "#" + kind;
  }

  private static <T> T instantiate(
      ModuleId id, String className, ModuleLayerHandle handle, Class<T> expectedType) {
    try {
      Class<?> clazz = Class.forName(className, true, handle.loader());
      Object instance = clazz.getDeclaredConstructor().newInstance();
      if (!expectedType.isInstance(instance)) {
        throw new IllegalStateException(
            "module "
                + id
                + " probe class "
                + className
                + " does not implement "
                + expectedType.getSimpleName());
      }
      return expectedType.cast(instance);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "module " + id + " failed to instantiate probe class " + className, e);
    }
  }
}
