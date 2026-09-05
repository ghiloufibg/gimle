package com.gimle.worker.testsupport;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.worker.InstanceIdentity;
import com.gimle.worker.InstanceIdentityRegistry;
import com.gimle.worker.WorkerRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The "load a real dynamically-loaded fixture module, wire it through a real {@link
 * ModuleController} whose event sink feeds a real {@link WorkerRuntime}" plumbing shared by {@code
 * WorkerRuntimeTest} and {@code JobHooksExecutionTest} -- only the {@code WorkerRuntime} tuning
 * (liveness threshold, stable-uptime window) and identity tracking differ per caller, not how the
 * pieces themselves are assembled.
 */
public final class WiredWorkerRuntime {

  private WiredWorkerRuntime() {}

  /** One {@link WorkerRuntime.HealthReportSink#report} call, captured for a test to assert on. */
  public record HealthUpdate(ModuleInstanceId id, boolean alive, boolean ready) {}

  /** One {@link WorkerRuntime.LivenessRestartSink#restartTriggered} call, likewise captured. */
  public record LivenessRestart(ModuleInstanceId id, int consecutiveFailures) {}

  public record Result(
      ModuleRegistry registry,
      ModuleController controller,
      WorkerRuntime runtime,
      ServiceRegistry serviceRegistry,
      ModuleInstanceId id,
      List<LifecycleEvent> events,
      List<HealthUpdate> healthUpdates,
      List<LivenessRestart> livenessRestarts) {}

  /** A fast probe cadence, so a test that isn't about probe timing itself never waits on one. */
  private static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofMillis(20);

  private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(1);

  /**
   * {@code stableUptimeThreshold} empty means "use {@link WorkerRuntime}'s own production default"
   * -- routed to the eight/ten-arg constructor rather than duplicating that default's value here.
   */
  public static Result start(
      Path jar,
      int livenessFailureThreshold,
      Optional<Duration> stableUptimeThreshold,
      Consumer<ModuleInstanceId> onRestartBudgetExhausted,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    return start(
        jar,
        DEFAULT_PROBE_INTERVAL,
        DEFAULT_PROBE_TIMEOUT,
        livenessFailureThreshold,
        stableUptimeThreshold,
        onRestartBudgetExhausted,
        identityRegistry,
        onInstanceUninstalled);
  }

  /**
   * The worker-wide probe defaults spelled out, for a test proving that a module's own
   * manifest-declared health timing takes precedence over them.
   */
  public static Result start(
      Path jar,
      Duration defaultProbeInterval,
      Duration defaultProbeTimeout,
      int livenessFailureThreshold,
      Optional<Duration> stableUptimeThreshold,
      Consumer<ModuleInstanceId> onRestartBudgetExhausted,
      InstanceIdentityRegistry identityRegistry,
      Consumer<InstanceIdentity> onInstanceUninstalled) {
    ModuleArtifact artifact = ModuleArtifactReader.read(jar);
    ModuleRegistry registry = new ModuleRegistry();
    ModuleInstanceId id = registry.register(artifact);
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ServiceRegistry serviceRegistry = new SimpleServiceRegistry();
    List<LifecycleEvent> events = new CopyOnWriteArrayList<>();
    List<HealthUpdate> healthUpdates = new CopyOnWriteArrayList<>();
    List<LivenessRestart> livenessRestarts = new CopyOnWriteArrayList<>();

    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          events.add(event);
          WorkerRuntime runtime = runtimeRef.get();
          if (runtime != null) {
            runtime.onLifecycleEvent(event);
          }
        };

    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            ClassLoader.getSystemClassLoader(),
            Duration.ofMillis(50),
            sink,
            serviceRegistry);

    WorkerRuntime.HealthReportSink healthReportSink =
        (moduleId, alive, ready) -> healthUpdates.add(new HealthUpdate(moduleId, alive, ready));
    WorkerRuntime runtime =
        new WorkerRuntime(
            controller,
            registry,
            serviceRegistry,
            4,
            defaultProbeInterval,
            defaultProbeTimeout,
            livenessFailureThreshold,
            onRestartBudgetExhausted,
            stableUptimeThreshold.orElse(WorkerRuntime.DEFAULT_STABLE_UPTIME_THRESHOLD),
            identityRegistry,
            onInstanceUninstalled,
            healthReportSink,
            (moduleId, consecutiveFailures) ->
                livenessRestarts.add(new LivenessRestart(moduleId, consecutiveFailures)));
    runtimeRef.set(runtime);

    controller.resolve(id);
    controller.start(id);

    return new Result(
        registry,
        controller,
        runtime,
        serviceRegistry,
        id,
        events,
        healthUpdates,
        livenessRestarts);
  }
}
