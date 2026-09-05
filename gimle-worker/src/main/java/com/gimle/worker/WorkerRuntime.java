package com.gimle.worker;

import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.HealthProbes;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleInstanceId;
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
  public static final Duration DEFAULT_STABLE_UPTIME_THRESHOLD = Duration.ofSeconds(10);

  private final ModuleController controller;
  private final ModuleRegistry registry;
  private final ServiceRegistry serviceRegistry;
  private final int defaultMaxConcurrency;
  private final Duration defaultProbeInterval;
  private final Duration defaultProbeTimeout;
  private final int defaultLivenessFailureThreshold;
  private final Consumer<ModuleInstanceId> onModuleRestartBudgetExhausted;
  private final Duration stableUptimeThreshold;
  private final InstanceIdentityRegistry identityRegistry;
  private final Consumer<InstanceIdentity> onInstanceUninstalled;
  private final HealthReportSink healthReportSink;
  private final LivenessRestartSink livenessRestartSink;

  private final Map<ModuleInstanceId, BoundedModuleScheduler> schedulers =
      new ConcurrentHashMap<>();
  private final Map<ModuleInstanceId, RestartTracker> restartTrackers = new ConcurrentHashMap<>();
  private final Map<ModuleInstanceId, AtomicInteger> consecutiveLivenessFailures =
      new ConcurrentHashMap<>();

  // The threshold actually in force for each currently-ACTIVE module: its own manifest's
  // health.failureThreshold, or this worker's default where it declares none. Recorded at ACTIVE
  // rather than re-read on every failure so onLivenessResult never has to reach back into the
  // registry for an artifact a concurrent uninstall may already have removed.
  private final Map<ModuleInstanceId, Integer> effectiveLivenessThresholds =
      new ConcurrentHashMap<>();
  private final Set<ModuleInstanceId> restartsInFlight = ConcurrentHashMap.newKeySet();
  private final ProbeLoop probeLoop = new ProbeLoop();

  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration defaultProbeInterval,
      Duration defaultProbeTimeout,
      int defaultLivenessFailureThreshold,
      Consumer<ModuleInstanceId> onModuleRestartBudgetExhausted) {
    this(
        controller,
        registry,
        serviceRegistry,
        defaultMaxConcurrency,
        defaultProbeInterval,
        defaultProbeTimeout,
        defaultLivenessFailureThreshold,
        onModuleRestartBudgetExhausted,
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        new InstanceIdentityRegistry(),
        identity -> {},
        (id, alive, ready) -> {},
        (id, consecutiveFailures) -> {});
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
      Duration defaultProbeInterval,
      Duration defaultProbeTimeout,
      int defaultLivenessFailureThreshold,
      Consumer<ModuleInstanceId> onModuleRestartBudgetExhausted,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    this(
        controller,
        registry,
        serviceRegistry,
        defaultMaxConcurrency,
        defaultProbeInterval,
        defaultProbeTimeout,
        defaultLivenessFailureThreshold,
        onModuleRestartBudgetExhausted,
        DEFAULT_STABLE_UPTIME_THRESHOLD,
        identityRegistry,
        onInstanceUninstalled,
        (id, alive, ready) -> {},
        (id, consecutiveFailures) -> {});
  }

  /**
   * Same as the eight/ten-arg constructors, with an explicit {@code stableUptimeThreshold} (see
   * {@link #DEFAULT_STABLE_UPTIME_THRESHOLD}) rather than the default -- for tests that need a
   * shorter window than the production default to stay fast -- and no {@link HealthReportSink}: a
   * caller that doesn't care to relay readiness anywhere still gets a fully working probe loop, it
   * simply has nowhere further to report to.
   */
  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration defaultProbeInterval,
      Duration defaultProbeTimeout,
      int defaultLivenessFailureThreshold,
      Consumer<ModuleInstanceId> onModuleRestartBudgetExhausted,
      Duration stableUptimeThreshold,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    this(
        controller,
        registry,
        serviceRegistry,
        defaultMaxConcurrency,
        defaultProbeInterval,
        defaultProbeTimeout,
        defaultLivenessFailureThreshold,
        onModuleRestartBudgetExhausted,
        stableUptimeThreshold,
        identityRegistry,
        onInstanceUninstalled,
        (id, alive, ready) -> {},
        (id, consecutiveFailures) -> {});
  }

  /**
   * The fullest constructor: every tuning knob explicit, plus a {@link HealthReportSink} -- {@code
   * WorkerMain} is the one real caller that supplies a sink that actually goes anywhere (relaying a
   * {@code HealthReport} up this worker's own control channel to the agent), since the agent
   * otherwise has no way to learn a hosted module's real readiness once it reaches ACTIVE; see
   * {@link #onReadinessResult}.
   */
  public WorkerRuntime(
      ModuleController controller,
      ModuleRegistry registry,
      ServiceRegistry serviceRegistry,
      int defaultMaxConcurrency,
      Duration defaultProbeInterval,
      Duration defaultProbeTimeout,
      int defaultLivenessFailureThreshold,
      Consumer<ModuleInstanceId> onModuleRestartBudgetExhausted,
      Duration stableUptimeThreshold,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled,
      HealthReportSink healthReportSink,
      LivenessRestartSink livenessRestartSink) {
    this.controller = controller;
    this.registry = registry;
    this.serviceRegistry = serviceRegistry;
    this.defaultMaxConcurrency = defaultMaxConcurrency;
    this.defaultProbeInterval = defaultProbeInterval;
    this.defaultProbeTimeout = defaultProbeTimeout;
    this.defaultLivenessFailureThreshold = defaultLivenessFailureThreshold;
    this.onModuleRestartBudgetExhausted = onModuleRestartBudgetExhausted;
    this.stableUptimeThreshold = stableUptimeThreshold;
    this.identityRegistry = identityRegistry;
    this.onInstanceUninstalled = onInstanceUninstalled;
    this.healthReportSink = healthReportSink;
    this.livenessRestartSink = livenessRestartSink;
  }

  /**
   * Notified with {@code id}'s current alive/ready state every time a probe tick changes what it
   * would report -- the seam a caller (only {@code WorkerMain} in production) uses to relay it
   * onward, e.g. as a wire {@code HealthReport}, without {@code gimle-worker} itself depending on
   * any wire-protocol type.
   */
  @FunctionalInterface
  public interface HealthReportSink {
    void report(ModuleInstanceId id, boolean alive, boolean ready);
  }

  /**
   * Notified at the moment a run of consecutive liveness-probe failures has actually triggered a
   * module-tier restart of {@code id}. Distinct from {@link HealthReportSink}, which reports what
   * every probe tick currently reads: this fires once per restart decision, so a caller (only
   * {@code WorkerMain} in production) can record the cause as its own durable timeline entry. The
   * restart's own STOPPING/UNINSTALLED/INSTALLED/ACTIVE run is reported the ordinary way and,
   * without this, is indistinguishable from an operator stopping and redeploying the instance.
   */
  @FunctionalInterface
  public interface LivenessRestartSink {
    void restartTriggered(ModuleInstanceId id, int consecutiveFailures);
  }

  /**
   * The concurrency-bounding {@link BoundedModuleScheduler} this runtime created for {@code id} at
   * its most recent {@code Active} transition, if any -- lets {@code FabricServer} route an inbound
   * call's actual invocation through the same per-module concurrency budget {@link ProbeLoop}
   * already uses for health checks, rather than real request traffic bypassing it entirely.
   */
  public Optional<BoundedModuleScheduler> schedulerFor(ModuleInstanceId id) {
    return Optional.ofNullable(schedulers.get(id));
  }

  /**
   * Every port {@code id}'s hook code has reported back via its own {@link
   * ModuleContext#reportPort}, if it has resolved a context at all -- empty for the overwhelming
   * majority of modules, which never call {@code reportPort}. Lets {@code WorkerMain}'s own
   * periodic metrics report fold a module's self-reported ports into the same {@code
   * ControlMessage.MetricsReport} it already sends, without that loop needing to reach into {@link
   * ModuleController} directly.
   */
  public Map<String, Integer> reportedPortsFor(ModuleInstanceId id) {
    return controller.context(id).map(ModuleContext::reportedPorts).orElse(Map.of());
  }

  public void onLifecycleEvent(LifecycleEvent event) {
    switch (event) {
      case LifecycleEvent.Active active -> onActive(active.id());
      case LifecycleEvent.Stopping stopping -> onStopping(stopping.id());
      case LifecycleEvent.Uninstalled uninstalled -> onUninstalled(uninstalled.id());
      default -> {}
    }
  }

  private void onActive(ModuleInstanceId id) {
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
    HealthProbes probes = descriptor.healthProbes();
    effectiveLivenessThresholds.put(
        id, probes.livenessFailureThreshold().orElse(defaultLivenessFailureThreshold));

    Optional<ModuleLayerHandle> handleOpt = registry.layerHandle(id);
    if (handleOpt.isEmpty()) {
      log.warn("module {} is ACTIVE but has no layer handle; skipping probe setup", id);
      return;
    }
    ModuleLayerHandle handle = handleOpt.get();

    // Each timing falls back to this worker's own default, so a module that declares none is
    // checked exactly as before.
    Duration interval = probes.interval().orElse(defaultProbeInterval);
    Duration timeout = probes.timeout().orElse(defaultProbeTimeout);
    // Absent means the no-initial-delay default: first tick fires one interval after ACTIVE, same
    // as every interval after it.
    Duration initialDelay = probes.initialDelay().orElse(interval);

    Optional<String> livenessClassName = probes.livenessClass();
    if (livenessClassName.isPresent()) {
      Optional<LivenessProbe> probe =
          instantiateOrFail(
              id, "liveness probe", livenessClassName.get(), handle, LivenessProbe.class);
      if (probe.isEmpty()) {
        // Already forced to FAILED inside instantiateOrFail -- an instance that never gets a
        // liveness probe registered has nothing further worth wiring up.
        return;
      }
      probeLoop.start(
          probeKey(id, "liveness"),
          scheduler,
          probe.get()::isAlive,
          interval,
          timeout,
          initialDelay,
          alive -> onLivenessResult(id, alive));
    }

    Optional<String> readinessClassName = probes.readinessClass();
    if (readinessClassName.isPresent()) {
      Optional<ReadinessProbe> probe =
          instantiateOrFail(
              id, "readiness probe", readinessClassName.get(), handle, ReadinessProbe.class);
      if (probe.isEmpty()) {
        return;
      }
      probeLoop.start(
          probeKey(id, "readiness"),
          scheduler,
          probe.get()::isReady,
          interval,
          timeout,
          initialDelay,
          ready -> onReadinessResult(id, ready));
    }

    descriptor
        .jobHooksClass()
        .ifPresent(
            className -> {
              Optional<JobHooks> hooks =
                  instantiateOrFail(id, "job hooks", className, handle, JobHooks.class);
              // Already forced to FAILED inside instantiateOrFail -- matches every other
              // instantiate-on-ACTIVE failure here (a probe class that fails to load gets the
              // identical treatment above); nothing left to run.
              hooks.ifPresent(h -> runJobHooks(id, h, mdcTags));
            });
  }

  /**
   * A probe or job-hooks class that fails to load or construct is discovered only after {@code
   * ModuleController#start} has already called {@code registry.markActive} and begun emitting the
   * {@code Active} event this method (called from {@link #onActive}) is reacting to -- without
   * this, {@link #instantiate}'s exception would propagate out of {@link #onActive} straight into
   * {@code ModuleController#emit}'s generic event-sink catch, which only logs it and drops it on
   * the floor. The instance would then sit ACTIVE forever with nothing further ever wired up and no
   * operator-visible signal that anything went wrong -- a manifest typo in {@code
   * health.liveness}/{@code health.readiness}/{@code lifecycle.jobHooks} silently stranding the
   * instance (a Job stuck this way never reaches {@code COMPLETED}, so nothing but its own {@code
   * activeDeadlineSeconds}, if it declares one, ever ends it). Forcing it to FAILED here instead
   * mirrors {@link #restartModule}'s own budget-exhaustion escalation: the same {@code
   * controller.forceFailed} call, the same durable {@code TransitionFailed} event on the far end.
   */
  private <T> Optional<T> instantiateOrFail(
      ModuleInstanceId id,
      String kind,
      String className,
      ModuleLayerHandle handle,
      Class<T> expectedType) {
    try {
      return Optional.of(instantiate(id, className, handle, expectedType));
    } catch (RuntimeException e) {
      log.error(
          "module {} failed to load its {} class {}: {}", id, kind, className, e.getMessage());
      try {
        controller.forceFailed(
            id, "failed to load " + kind + " class " + className + ": " + e.getMessage());
      } catch (RuntimeException forceFailedFailure) {
        // Best-effort, matching restartModule's own forceFailed guard: a concurrent transition
        // (e.g. a racing uninstall) can move this module off ACTIVE before this call lands, and
        // losing that race must not crash the worker tick.
        log.warn(
            "could not force module {} to FAILED after {} load failure: {}",
            id,
            kind,
            forceFailedFailure.getMessage());
      }
      return Optional.empty();
    }
  }

  /**
   * A Job-kind module declares {@code lifecycle.jobHooks} instead of {@code health.liveness}/{@code
   * .readiness} -- there's nothing to probe, only a unit of work to run to completion, exactly
   * once, on its own virtual thread so a long-running (or blocking) {@link JobHooks#run} never ties
   * up a probe-loop or control-channel thread. The result -- or a thrown exception, treated the
   * same as an explicit {@link CompletionStatus#FAILED} -- feeds {@link ModuleController#complete},
   * which drives the {@code ACTIVE -&gt; COMPLETED}/{@code ACTIVE -&gt; FAILED} transition and its
   * {@link LifecycleEvent} the same {@link #onLifecycleEvent} sink every other transition already
   * flows through. {@code hooks} is already a live instance -- see {@link #instantiateOrFail},
   * whose failure this method never has to handle since {@link #onActive} only calls this once that
   * already succeeded.
   *
   * <p>{@code mdcTags} is wrapped around the whole virtual thread body via {@link
   * InstanceMdcContext#runTagged} -- unlike {@code WorkerMain#runCommand}'s synchronous hooks
   * (onInstall/onStart/onStop/onUninstall), which inherit the calling control-channel thread's
   * already-tagged MDC for free, this thread is a brand-new one with no MDC of its own: without
   * this, every line {@link JobHooks#run} (and anything it calls, including a Job's own fabric
   * fan-out) logs lands in the worker's shared PLATFORM log instead of this instance's own
   * APPLICATION log -- indistinguishable from the run never having happened at all when read back
   * through this instance's own per-instance log file.
   */
  private void runJobHooks(ModuleInstanceId id, JobHooks hooks, Map<String, String> mdcTags) {
    ModuleContext ctx =
        controller
            .context(id)
            .orElseThrow(
                () -> new IllegalStateException("module " + id + " is ACTIVE but has no context"));
    Thread.ofVirtual()
        .name("gimle-job-" + id.name() + "-" + id.version())
        .start(
            () -> {
              try {
                InstanceMdcContext.runTagged(
                    mdcTags,
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
                        // The module already left ACTIVE some other way (e.g. an operator
                        // uninstalled it mid-run) between hooks.run() returning and this call --
                        // best-effort, matching restartModule's own "lost race against a
                        // concurrent transition" posture.
                        log.warn("could not complete job {}: {}", id, e.getMessage());
                      }
                      return null;
                    });
              } catch (Exception e) {
                // Callable<Void>'s signature declares a checked Exception that the lambda above
                // never actually throws -- same "impossible in practice" shape WorkerMain#
                // runCommand's own identical wrapping already has to satisfy the compiler for.
                throw new IllegalStateException("unexpected checked exception from job " + id, e);
              }
            });
  }

  private void onStopping(ModuleInstanceId id) {
    probeLoop.stop(probeKey(id, "liveness"));
    probeLoop.stop(probeKey(id, "readiness"));
    serviceRegistry.markUnready(id);
  }

  private void onUninstalled(ModuleInstanceId id) {
    // Looked up before serviceRegistry.remove(id) below, which is what actually clears
    // identityRegistry (InstanceTaggingServiceRegistry#remove) -- read it first or it's gone.
    identityRegistry.lookup(id).ifPresent(onInstanceUninstalled);
    BoundedModuleScheduler scheduler = schedulers.remove(id);
    if (scheduler != null) {
      scheduler.close();
    }
    restartTrackers.remove(id);
    consecutiveLivenessFailures.remove(id);
    effectiveLivenessThresholds.remove(id);
    serviceRegistry.remove(id);
  }

  private void onReadinessResult(ModuleInstanceId id, boolean ready) {
    if (ready) {
      serviceRegistry.markReady(id);
    } else {
      serviceRegistry.markUnready(id);
    }
    // "alive" here is always true: this tick only ran because the module is still ACTIVE with a
    // live probe loop. A genuine liveness failure travels its own two routes -- livenessRestartSink
    // when it triggers a restart, and ModuleStateChanged("FAILED") once restartModule's own budget
    // is exhausted -- never this one, so it isn't duplicated or raced against here.
    healthReportSink.report(id, true, ready);
  }

  private void onLivenessResult(ModuleInstanceId id, boolean alive) {
    if (alive) {
      consecutiveLivenessFailures.computeIfAbsent(id, key -> new AtomicInteger()).set(0);
      return;
    }
    int failures =
        consecutiveLivenessFailures
            .computeIfAbsent(id, key -> new AtomicInteger())
            .incrementAndGet();
    if (failures < effectiveLivenessThresholds.getOrDefault(id, defaultLivenessFailureThreshold)) {
      return;
    }
    consecutiveLivenessFailures.get(id).set(0);
    // Reported before the restart rather than after: restartModule's own stop() synchronously
    // reaches onUninstalled(), which drops this module's identity, so a sink that looked the
    // identity up afterwards would have nothing left to attach the event to.
    livenessRestartSink.restartTriggered(id, failures);
    restartModule(id);
  }

  private void restartModule(ModuleInstanceId id) {
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
        // abandonFailed rather than forceFailed: a restart attempt that already drove this module
        // through UNINSTALLED leaves nothing ACTIVE to force, and that is exactly the case that
        // used to strand the instance with no terminal state at all.
        controller.abandonFailed(id, "restart budget exhausted");
      } catch (RuntimeException e) {
        log.warn("could not mark module {} FAILED after budget exhaustion: {}", id, e.getMessage());
      }
      onModuleRestartBudgetExhausted.accept(id);
      return;
    }

    // Captured now, while the module still exists: ModuleController#stop() drains and fully
    // uninstalls (removes it from the registry entirely, per the UNINSTALLED -> [*] terminal),
    // so re-resolving the same id afterward would throw NoSuchElementException unless the
    // artifact is re-registered first. "Dispose and reinstantiate" genuinely means reinstall,
    // not just resolve+start on an id that's still sitting there.
    //
    // Best-effort, matching the forceFailed guard above: a concurrent uninstall can remove this
    // module from the registry between the guard-add above and this lookup, and losing that race
    // must not permanently bar this id from ever restarting again.
    ModuleArtifact artifact;
    try {
      artifact = registry.artifact(id);
    } catch (RuntimeException e) {
      log.warn("could not look up artifact for module {} to restart: {}", id, e.getMessage());
      restartsInFlight.remove(id);
      return;
    }

    Duration delay = tracker.delayUntilNextAttempt(now);
    Runnable attempt =
        () -> {
          boolean succeeded = false;
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
              succeeded = true;
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
          // Re-enters the same backoff loop rather than dead-ending here. controller.stop() has
          // already driven the module to UNINSTALLED by this point, so an attempt that then failed
          // to bring it back leaves the instance dead with no further probe ticking to notice --
          // nothing else would ever try again, and nothing would report it failed either. The
          // tracker's own budget bounds this: once it refuses, the branch above escalates.
          if (!succeeded) {
            restartModule(id);
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
  private void scheduleModuleStabilityConfirmation(ModuleInstanceId id, RestartTracker tracker) {
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

  private static String probeKey(ModuleInstanceId id, String kind) {
    return id + "#" + kind;
  }

  private static <T> T instantiate(
      ModuleInstanceId id, String className, ModuleLayerHandle handle, Class<T> expectedType) {
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
