package com.gimle.worker;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.layer.ModuleLayerHandle;
import com.gimle.module.lifecycle.CompletionStatus;
import com.gimle.module.lifecycle.JobHooks;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleContext;
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
 * The event-driven glue between {@link ModuleController}'s module lifecycle and this worker's own
 * concerns: creates/disposes each module's {@link BoundedModuleScheduler} and probes in lockstep
 * with {@link LifecycleEvent}s, escalates repeated liveness failures to a module restart (and, once
 * that module's own restart budget is exhausted, to giving up on the whole worker), and owns the
 * {@code Stopping}-vs-{@code Uninstalled} service-registry teardown timing that {@link
 * ModuleController} deliberately leaves to its caller.
 *
 * <p>{@link #onLifecycleEvent} is exactly the {@code Consumer<LifecycleEvent>} {@link
 * ModuleController}'s constructor already accepts -- no changes to {@code gimle-module} were needed
 * to wire this in, confirming that event sink was designed generally enough from the start.
 */
public final class WorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

  /**
   * How long a module-tier restart must stay uneventful (no further {@code
   * recordFailureAndCheckShouldRetry} against the same {@link RestartTracker}) before {@link
   * #scheduleModuleStabilityConfirmation} resets its backoff budget -- matches {@code
   * WorkerProcessSupervisor#DEFAULT_STABLE_UPTIME_THRESHOLD} one tier up, same reasoning: a restart
   * attempt not throwing proves nothing about whether the module is actually healthy.
   */
  static final Duration DEFAULT_STABLE_UPTIME_THRESHOLD = Duration.ofSeconds(10);

  private final ModuleController controller;
  private final ModuleRegistry registry;
  private final ServiceRegistry serviceRegistry;
  private final int defaultMaxConcurrency;
  private final Duration probeInterval;
  private final Duration probeTimeout;
  private final int livenessFailureThreshold;
  private final Consumer<ModuleId> onModuleRestartBudgetExhausted;
  private final Duration stableUptimeThreshold;
  private final InstanceIdentityRegistry identityRegistry;
  private final Consumer<InstanceIdentity> onInstanceUninstalled;

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
    this(
        controller,
        registry,
        serviceRegistry,
        defaultMaxConcurrency,
        probeInterval,
        probeTimeout,
        livenessFailureThreshold,
        onModuleRestartBudgetExhausted,
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        new InstanceIdentityRegistry(),
        identity -> {});
  }

  /**
   * {@code identityRegistry}/{@code onInstanceUninstalled}: looked up in {@link #onActive} to tag
   * this module's probe-check scheduler with its instance identity, and consulted in {@link
   * #onUninstalled} (before {@code serviceRegistry.remove} clears it) to let the caller close that
   * instance's sifted log file.
   */
  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration probeInterval,
      Duration probeTimeout,
      int livenessFailureThreshold,
      Consumer<ModuleId> onModuleRestartBudgetExhausted,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    this(
        controller,
        registry,
        serviceRegistry,
        defaultMaxConcurrency,
        probeInterval,
        probeTimeout,
        livenessFailureThreshold,
        onModuleRestartBudgetExhausted,
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        identityRegistry,
        onInstanceUninstalled);
  }

  /**
   * Same as the eight/ten-arg constructors, with an explicit {@code stableUptimeThreshold} (see
   * {@link #DEFAULT_STABLE_UPTIME_THRESHOLD}) rather than the default -- for tests that need a
   * shorter window than the production default to stay fast.
   */
  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration probeInterval,
      Duration probeTimeout,
      int livenessFailureThreshold,
      Consumer<ModuleId> onModuleRestartBudgetExhausted,
      Duration stableUptimeThreshold,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    this.controller = controller;
    this.registry = registry;
    this.serviceRegistry = serviceRegistry;
    this.defaultMaxConcurrency = defaultMaxConcurrency;
    this.probeInterval = probeInterval;
    this.probeTimeout = probeTimeout;
    this.livenessFailureThreshold = livenessFailureThreshold;
    this.onModuleRestartBudgetExhausted = onModuleRestartBudgetExhausted;
    this.stableUptimeThreshold = stableUptimeThreshold;
    this.identityRegistry = identityRegistry;
    this.onInstanceUninstalled = onInstanceUninstalled;
  }

  /**
   * The concurrency-bounding {@link BoundedModuleScheduler} this runtime created for {@code id} at
   * its most recent {@code Active} transition, if any -- lets {@code FabricServer} route an inbound
   * call's actual invocation through the same per-module concurrency budget {@link ProbeLoop}
   * already uses for health checks, rather than real request traffic bypassing it entirely.
   */
  public Optional<BoundedModuleScheduler> schedulerFor(ModuleId id) {
    return Optional.ofNullable(schedulers.get(id));
  }

  public void onLifecycleEvent(LifecycleEvent event) {
    switch (event) {
      case LifecycleEvent.Active active -> onActive(active.id());
      case LifecycleEvent.Stopping stopping -> onStopping(stopping.id());
      case LifecycleEvent.Uninstalled uninstalled -> onUninstalled(uninstalled.id());
      default -> {}
    }
  }

  private void onActive(ModuleId id) {
    Map<String, String> mdcTags =
        identityRegistry
            .lookup(id)
            .map(
                identity ->
                    InstanceMdcContext.tagsFor(
                        identity.deploymentName(),
                        identity.instanceIndex(),
                        id.name(),
                        id.version().toString(),
                        identity.tenantId().orElse(null)))
            .orElse(Map.of());
    BoundedModuleScheduler scheduler =
        new BoundedModuleScheduler(id, defaultMaxConcurrency, mdcTags);
    schedulers.put(id, scheduler);
    restartTrackers.computeIfAbsent(id, key -> newRestartTracker());
    consecutiveLivenessFailures.computeIfAbsent(id, key -> new AtomicInteger());

    ModuleDescriptor descriptor = registry.artifact(id).descriptor();
    Optional<ModuleLayerHandle> handleOpt = registry.layerHandle(id);
    if (handleOpt.isEmpty()) {
      log.warn("module {} is ACTIVE but has no layer handle; skipping probe setup", id);
      return;
    }
    ModuleLayerHandle handle = handleOpt.get();

    // Absent means the pre-P2-4 default: first tick fires one probeInterval after ACTIVE, same as
    // every interval after it -- ProbeLoop's own back-compat overload handles that when passed
    // probeInterval unchanged here.
    Duration initialDelay = descriptor.healthProbes().initialDelay().orElse(probeInterval);

    descriptor
        .healthProbes()
        .livenessClass()
        .ifPresent(
            className -> {
              LivenessProbe probe = instantiate(id, className, handle, LivenessProbe.class);
              probeLoop.start(
                  probeKey(id, "liveness"),
                  scheduler,
                  probe::isAlive,
                  probeInterval,
                  probeTimeout,
                  initialDelay,
                  alive -> onLivenessResult(id, alive));
            });

    descriptor
        .healthProbes()
        .readinessClass()
        .ifPresent(
            className -> {
              ReadinessProbe probe = instantiate(id, className, handle, ReadinessProbe.class);
              probeLoop.start(
                  probeKey(id, "readiness"),
                  scheduler,
                  probe::isReady,
                  probeInterval,
                  probeTimeout,
                  initialDelay,
                  ready -> onReadinessResult(id, ready));
            });

    descriptor.jobHooksClass().ifPresent(className -> runJobHooks(id, className, handle));
  }

  /**
   * Priority-3 design doc §3a/§3b: a Job-kind module declares {@code lifecycle.jobHooks} instead of
   * {@code health.liveness}/{@code .readiness} -- there's nothing to probe, only a unit of work to
   * run to completion, exactly once, on its own virtual thread so a long-running (or blocking)
   * {@link JobHooks#run} never ties up a probe-loop or control-channel thread. The result -- or a
   * thrown exception, treated the same as an explicit {@link CompletionStatus#FAILED} -- feeds
   * {@link ModuleController#complete}, which drives the {@code ACTIVE -&gt; COMPLETED}/{@code
   * ACTIVE -&gt; FAILED} transition and its {@link LifecycleEvent} the same {@link
   * #onLifecycleEvent} sink every other transition already flows through.
   */
  private void runJobHooks(ModuleId id, String className, ModuleLayerHandle handle) {
    JobHooks hooks = instantiate(id, className, handle, JobHooks.class);
    ModuleContext ctx =
        controller
            .context(id)
            .orElseThrow(
                () -> new IllegalStateException("module " + id + " is ACTIVE but has no context"));
    Thread.ofVirtual()
        .name("gimle-job-" + id.name() + "-" + id.version())
        .start(
            () -> {
              CompletionStatus status;
              try {
                status = hooks.run(ctx);
              } catch (RuntimeException e) {
                log.warn("job {} run threw: {}", id, e.getMessage());
                status = CompletionStatus.FAILED;
              }
              try {
                controller.complete(id, status);
              } catch (RuntimeException e) {
                // The module already left ACTIVE some other way (e.g. an operator uninstalled it
                // mid-run) between hooks.run() returning and this call -- best-effort, matching
                // restartModule's own "lost race against a concurrent transition" posture.
                log.warn("could not complete job {}: {}", id, e.getMessage());
              }
            });
  }

  private void onStopping(ModuleId id) {
    probeLoop.stop(probeKey(id, "liveness"));
    probeLoop.stop(probeKey(id, "readiness"));
    serviceRegistry.markUnready(id);
  }

  private void onUninstalled(ModuleId id) {
    // Looked up before serviceRegistry.remove(id) below, which is what actually clears
    // identityRegistry (InstanceTaggingServiceRegistry#remove) -- read it first or it's gone.
    identityRegistry.lookup(id).ifPresent(onInstanceUninstalled);
    BoundedModuleScheduler scheduler = schedulers.remove(id);
    if (scheduler != null) {
      scheduler.close();
    }
    restartTrackers.remove(id);
    consecutiveLivenessFailures.remove(id);
    serviceRegistry.remove(id);
  }

  private void onReadinessResult(ModuleId id, boolean ready) {
    if (ready) {
      serviceRegistry.markReady(id);
    } else {
      serviceRegistry.markUnready(id);
    }
  }

  private void onLivenessResult(ModuleId id, boolean alive) {
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
    restartModule(id);
  }

  private void restartModule(ModuleId id) {
    // The probe loop keeps ticking the (still ACTIVE-until-the-attempt-actually-runs) module the
    // whole time an attempt is in flight, so without this guard every subsequent liveness failure
    // during that window would trigger its own concurrent stop()/resolve()/start() sequence,
    // racing the first attempt and tripping over a module the first attempt already moved on
    // from. One restart in flight per module at a time.
    if (!restartsInFlight.add(id)) {
      return;
    }

    RestartTracker tracker = restartTrackers.computeIfAbsent(id, key -> newRestartTracker());
    Instant now = Instant.now();
    if (!tracker.recordFailureAndCheckShouldRetry(now)) {
      log.error("module {} exhausted its restart budget; giving up on this worker", id);
      restartsInFlight.remove(id);
      // Escalate to FAILED rather than leaving the module ACTIVE-but-permanently-broken: this is
      // what makes AgentMain's alive flag flip and HealthReconciler's machine-tier reschedule
      // fire, completing the module -> worker -> machine escalation chain instead of dead-ending
      // here. Best-effort: a lost race against some other concurrent transition shouldn't crash
      // the worker tick over a module that's already leaving ACTIVE anyway.
      try {
        controller.forceFailed(id, "restart budget exhausted");
      } catch (RuntimeException e) {
        log.warn(
            "could not force module {} to FAILED after budget exhaustion: {}", id, e.getMessage());
      }
      onModuleRestartBudgetExhausted.accept(id);
      return;
    }

    // Captured now, while the module still exists: ModuleController#stop() drains and fully
    // uninstalls (removes it from the registry entirely, per the UNINSTALLED -> [*] terminal),
    // so re-resolving the same id afterward would throw NoSuchElementException unless the
    // artifact is re-registered first. "Dispose and reinstantiate" genuinely means reinstall,
    // not just resolve+start on an id that's still sitting there.
    var artifact = registry.artifact(id);

    Duration delay = tracker.delayUntilNextAttempt(now);
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
              // controller.stop(id) above drove the module through UNINSTALLED, which fired
              // onUninstalled() and removed this same tracker from restartTrackers; controller
              // .start(id) then fired onActive(), which found nothing there and created a
              // brand-new one. Put the ORIGINAL back -- the one carrying this cycle's actual
              // attempt count -- so a subsequent restart within the same window sees real
              // accumulated history instead of a falsely-fresh budget. Whether that history
              // actually gets reset is scheduleModuleStabilityConfirmation's call, not this line's.
              restartTrackers.put(id, tracker);
              scheduleModuleStabilityConfirmation(id, tracker);
            } catch (RuntimeException e) {
              log.warn("module {} restart attempt failed: {}", id, e.getMessage());
            }
          } finally {
            restartsInFlight.remove(id);
          }
        };
    // Deliberately not run via this module's own BoundedModuleScheduler: controller.stop(id)
    // above synchronously reaches onUninstalled(), which closes that very scheduler -- an
    // ExecutorService#close() call blocks awaiting termination of its own in-flight tasks, so
    // running the restart on the scheduler it's about to close would deadlock the restart against
    // itself. Restarting is worker-orchestration, not module request work, so it doesn't belong
    // on the module's own bounded concurrency budget anyway.
    Thread.ofVirtual().name("gimle-restart-" + id.name() + "-" + id.version()).start(attempt);
  }

  /**
   * Only calls {@link RestartTracker#recordSuccess()} once the module has stayed on this same
   * restart attempt -- no further {@code recordFailureAndCheckShouldRetry} call recorded against
   * {@code tracker} in the meantime -- for {@link #stableUptimeThreshold}. Calling {@code
   * recordSuccess()} immediately after {@code controller.start(id)} returns (the previous behavior)
   * would defeat backoff escalation entirely for a module that starts cleanly every time but never
   * becomes genuinely healthy: {@code start()} not throwing proves nothing about whether the module
   * actually works, only {@link #onLivenessResult} finding out later does -- the same reasoning
   * {@code WorkerProcessSupervisor}'s own {@code scheduleStabilityConfirmation} already applies one
   * tier up, mirrored here rather than duplicated by coincidence.
   */
  private void scheduleModuleStabilityConfirmation(ModuleId id, RestartTracker tracker) {
    int attemptsAtRestart = tracker.attemptsInWindow();
    Thread.ofVirtual()
        .name("gimle-restart-stability-" + id.name() + "-" + id.version())
        .start(
            () -> {
              try {
                Thread.sleep(stableUptimeThreshold);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              if (tracker.attemptsInWindow() == attemptsAtRestart) {
                tracker.recordSuccess();
              }
            });
  }

  private static RestartTracker newRestartTracker() {
    return new RestartTracker(
        Duration.ofMillis(100), 2.0, Duration.ofSeconds(5), 5, Duration.ofSeconds(60));
  }

  private static String probeKey(ModuleId id, String kind) {
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
