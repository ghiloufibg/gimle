package com.gimle.worker;

import com.gimle.core.banner.GimleBanner;
import com.gimle.core.banner.GimleVersion;
import com.gimle.core.exception.GimleLifecycleException;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.InstanceLogCloser;
import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.fabric.registry.FabricServiceRegistry;
import com.gimle.fabric.transport.FabricServer;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.layer.PlatformLayer;
import com.gimle.module.leak.LeakTracker;
import com.gimle.module.lifecycle.ControlPlaneRelayClient;
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.observability.GimleTracing;
import com.gimle.observability.MeterSnapshotCodec;
import com.gimle.observability.WorkerMetrics;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker JVM's entry point: connects out to the agent's control socket, then treats every
 * module operation -- including the very first module this worker ever hosts -- as arriving over
 * that channel. There's deliberately no separate "initial load" path: a freshly-started worker and
 * one mid-redeploy look identical from here.
 *
 * <p>Also binds a {@link FabricServer} (one UDS listener for same-machine callers, one TCP listener
 * for cross-machine callers) and wraps the worker's {@link SimpleServiceRegistry} in a {@link
 * FabricServiceRegistry}, so services registered here become reachable from other workers on this
 * machine and other nodes in the cluster, not just from other modules in this same worker.
 */
public final class WorkerMain {

  private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

  // How long a disposed module's classloader gets to actually be collected before
  // LeakTracker reports it as leaked (CLAUDE.md's "Classloader leak detection is first-class").
  // Long enough that ordinary GC latency under real load never produces a false positive; short
  // enough that a genuine leak is caught well within a single redeploy-in-a-loop QA pass rather
  // than only showing up as eventual metaspace exhaustion.
  private static final Duration LEAK_DETECTION_WINDOW = Duration.ofSeconds(30);

  // The same cadence AgentMain's own MuninnShipper instances tick on -- no correctness reason the
  // two need to match exactly, but keeping them equal means a worker's snapshot and its agent's
  // relay of it are never more than one tick apart from each other.
  private static final Duration MUNINN_SHIP_INTERVAL = Duration.ofSeconds(5);

  private WorkerMain() {}

  public static void main(String[] args) throws IOException {
    // Suppressed by default when gimle-agent spawns this process (AgentMain#buildWorkerCommand
    // sets -Dgimle.banner.enabled=false unconditionally) since a worker starts once per module
    // instance rather than once per node/replica lifecycle; still prints when WorkerMain is run
    // directly (manual testing, gimle:worker-style standalone use).
    GimleBanner.print(
        System.out,
        Map.of(
            "app.name", "Gimlé Worker",
            "app.description", "module hosting runtime",
            "app.version", GimleVersion.current()));
    if (args.length != 3) {
      System.err.println("usage: WorkerMain <nodeId> <tenantId-or-empty> <control-socket-path>");
      System.exit(2);
      return;
    }
    String nodeId = args[0];
    // The deployment's tenant, if any, is passed the same way node id and gossip seeds already
    // are: as a positional CLI argument set by AgentMain's bootstrapping. A blank argument means
    // untenanted, matching every other optional field's "absent means today's unchanged
    // behavior" convention. Always present (never a variable-arity 2-vs-3 form) so
    // WorkerProcessSupervisor's own "controlSocketPath is always the last appended argument"
    // invariant stays true regardless of whether this instance has a tenant.
    Optional<String> tenantId = args[1].isBlank() ? Optional.empty() : Optional.of(args[1]);

    InstanceLogCloser instanceLogCloser = setUpLogging(nodeId);

    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(args[2]));
    ControlChannelClient channel =
        ControlChannelClient.connectWithRetry(
            address, Duration.ofMillis(200), Duration.ofSeconds(30));
    log.info("connected to agent control socket at {}", address);

    long pid = ProcessHandle.current().pid();
    String workerId = "worker-" + pid;
    ControlPlaneRelay relay = new ControlPlaneRelay(channel);

    // A worker has no outbound network identity of its own -- every exported span relays to the
    // agent over this same control channel rather than shipping to Muninn directly, replacing
    // installDefault()'s previous behavior (LoggingSpanExporter, spans real and correctly parented,
    // just not shipped anywhere) rather than keeping it as a fallback: RelayingSpanExporter
    // degrades
    // to "the agent has nothing configured to forward to" exactly the same way an unset
    // gimle.agent.muninnEndpoint already does on the agent side, so there's no case where
    // installDefault() behaves differently from this in a way worth keeping.
    GimleTracing.install(
        new RelayingSpanExporter(workerId, message -> sendQuietly(channel, message)));

    ModuleRegistry registry = new ModuleRegistry();
    ModuleResolver resolver = new ModuleResolver(registry);
    ModuleLayer platform = PlatformLayer.bootOnly().layer();
    ClassLoader interfaceLoader = ClassLoader.getSystemClassLoader();

    SimpleServiceRegistry localRegistry = new SimpleServiceRegistry();
    InstanceIdentityRegistry identityRegistry = new InstanceIdentityRegistry();
    ServiceRegistry taggedLocal =
        new InstanceTaggingServiceRegistry(localRegistry, identityRegistry);
    ServiceCatalog catalog = new ServiceCatalog();
    MemberId selfNode = new MemberId(nodeId, new InetSocketAddress(0));
    // Constructed here, ahead of controller/runtime/FabricServer below, so buildFabricRegistry can
    // wire it in for the client (outbound-call) side of fabric request metrics -- the same registry
    // instance bindFabricServer later wires in for the server (inbound-dispatch) side.
    WorkerMetrics workerMetrics = new WorkerMetrics();
    FabricServiceRegistry fabricRegistry =
        buildFabricRegistry(
            selfNode,
            workerId,
            taggedLocal,
            catalog,
            registry,
            channel,
            interfaceLoader,
            tenantId,
            workerMetrics);

    // Every module this worker currently has ACTIVE -- fed to the metrics-reporter loop below,
    // which has no other way to know which module ids to report against (ModuleRegistry exposes
    // no "list everything" query, only lookups by a name/id it's told).
    Set<ModuleId> activeModules = ConcurrentHashMap.newKeySet();
    ControllerAndRuntime controllerAndRuntime =
        buildControllerAndRuntime(
            registry,
            resolver,
            platform,
            interfaceLoader,
            fabricRegistry,
            channel,
            identityRegistry,
            instanceLogCloser,
            activeModules,
            relay,
            nodeId);
    ModuleController controller = controllerAndRuntime.controller();
    WorkerRuntime runtime = controllerAndRuntime.runtime();

    // FabricServer is only bound now that controller/runtime exist: it routes an inbound call's
    // actual invocation through the target module's own ModuleContext (drain-visible in-flight
    // count) and BoundedModuleScheduler (real concurrency bound, not just probe checks), both of
    // which only exist once a module has gone ACTIVE through this same controller/runtime pair.
    // workerMetrics itself was already constructed above, ahead of fabricRegistry.
    FabricBinding fabricBinding =
        bindFabricServer(
            taggedLocal,
            interfaceLoader,
            controller,
            runtime,
            workerMetrics,
            registry,
            tenantId,
            identityRegistry);
    FabricEndpoints fabricEndpoints = fabricBinding.endpoints();
    startBackgroundWork(
        fabricBinding.server(), channel, workerId, activeModules, workerMetrics, runtime);

    channel.send(
        new ControlMessage.Hello(
            workerId,
            pid,
            fabricEndpoints.udsPath(),
            fabricEndpoints.tcpAddress().getHostString(),
            fabricEndpoints.tcpAddress().getPort()));

    if (isAotTrainingMode()) {
      // Training-only: this worker exists solely to populate a JDK AOT cache with the classes its
      // own pre-ready boot path touches (JEP 514 assembles the cache at JVM exit, and only a
      // clean exit triggers that -- destroyForcibly, this process's normal shutdown, never would).
      // No hosted module is ever installed above this line, so the cache this produces holds no
      // tenant or module bytes. Shared prerequisite for both the Sleipnir startup-cache benchmark
      // and its agent-managed trainer, not throwaway scaffolding.
      channel.close();
      return;
    }

    Optional<ControlMessage> received;
    while ((received = channel.receive()).isPresent()) {
      handle(
          received.get(),
          registry,
          controller,
          channel,
          catalog,
          identityRegistry,
          tenantId,
          workerId,
          workerMetrics,
          relay,
          fabricBinding.server());
    }
    log.info("control channel closed by agent; shutting down");
    // The control channel closing is the only signal this process gets that its agent is gone --
    // gracefully, killed outright, OOM-killed, or its host crashed, all indistinguishable from here
    // and all producing the identical EOF on this socket. Returning from main() and hoping the JVM
    // exits on its own is not reliable enough to depend on: any hosted module is free to have
    // started its own non-daemon thread (a thread pool, an embedded server) as completely ordinary
    // application behavior, which would silently keep this JVM alive forever as an orphan --
    // exactly
    // the failure a real hard-kill of the node agent surfaced. An explicit exit forces termination
    // regardless of what a hosted module left running, while still running shutdown hooks (needed
    // for the AOT-cache-assembly-at-clean-exit path above).
    System.exit(0);
  }

  /**
   * True when this worker was launched purely to populate a JDK AOT cache: it should complete its
   * normal pre-ready boot, send Hello, then exit cleanly rather than enter the receive loop.
   */
  /** Bridges the worker's own control-channel relay onto the module-facing client shape. */
  private static ControlPlaneRelayClient relayClient(ControlPlaneRelay relay) {
    return new ControlPlaneRelayClient() {
      @Override
      public ModuleContext.RelayResult read(String path) {
        return relay.request(path);
      }

      @Override
      public ModuleContext.RelayResult putResourceStatus(
          String kindName, Optional<String> tenantId, String name, String statusJson) {
        return relay.requestStatusPut(kindName, tenantId, name, statusJson);
      }
    };
  }

  private static boolean isAotTrainingMode() {
    return Boolean.getBoolean("gimle.worker.aotTraining");
  }

  /**
   * Sets the process-global properties {@code JsonLogEncoder} and the file appenders below need,
   * then attaches this worker's own platform log file plus the per-instance sifting appender.
   */
  private static InstanceLogCloser setUpLogging(String nodeId) {
    // Read fresh by JsonLogEncoder on every event (process-global, not thread-local, so this is
    // safe however early other threads start logging) and by the two file appenders attached
    // just below, which need the actual path now rather than at logback.xml parse time (which
    // already happened, before this line, via the CONSOLE appender).
    System.setProperty("gimle.process.role", "WORKER");
    System.setProperty("gimle.node.id", nodeId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("worker-platform.log"));
    return GimleLogging.attachInstanceSiftingAppender(logRoot.resolve("instances"));
  }

  /**
   * Builds this worker's {@link FabricServiceRegistry}, tuned with the circuit-breaker/ejection
   * constants every worker uses today (no per-deployment override exists yet).
   */
  private static FabricServiceRegistry buildFabricRegistry(
      MemberId selfNode,
      String workerId,
      ServiceRegistry taggedLocal,
      ServiceCatalog catalog,
      ModuleRegistry registry,
      ControlChannelClient channel,
      ClassLoader interfaceLoader,
      Optional<String> tenantId,
      WorkerMetrics workerMetrics) {
    // Forwarded by AgentMain's buildWorkerCommand as an explicit -D flag on every worker it
    // spawns; defaults to false (today's unchanged behavior) if somehow absent, e.g. a worker
    // launched by hand outside the agent.
    boolean defaultDenyCrossTenant =
        Boolean.parseBoolean(System.getProperty("gimle.fabric.defaultDenyCrossTenant", "false"));
    // Circuit-breaker tuning for cross-worker/cross-machine calls this registry proxies: trip
    // after BREAKER_WINDOW_SIZE outcomes with an error rate at or above
    // BREAKER_ERROR_RATE_THRESHOLD,
    // stay open for BREAKER_COOLDOWN before probing again.
    int breakerWindowSize = 5;
    double breakerErrorRateThreshold = 0.5;
    Duration breakerCooldown = Duration.ofSeconds(5);
    // The panic-mode ceiling on how much of a service's replica pool outlier-ejection may remove
    // at once, distinct from breakerErrorRateThreshold despite sharing the same 0.5 value here --
    // see FabricServiceRegistry.DEFAULT_MAX_EJECTION_PERCENT for why that default was chosen.
    double maxEjectionPercent = 0.5;
    return new FabricServiceRegistry(
        selfNode,
        workerId,
        taggedLocal,
        catalog,
        owner -> registry.artifact(owner).descriptor().exports(),
        message -> sendQuietly(channel, message),
        interfaceLoader,
        breakerWindowSize,
        breakerErrorRateThreshold,
        breakerCooldown,
        tenantId,
        maxEjectionPercent,
        defaultDenyCrossTenant,
        Optional.of(workerMetrics));
  }

  /**
   * Wires the lifecycle sink, leak tracker, {@link ModuleController}, and {@link WorkerRuntime}
   * together -- the sink needs a reference to the runtime it's feeding before that runtime exists,
   * hence the {@link AtomicReference} set only once construction below completes.
   */
  private static ControllerAndRuntime buildControllerAndRuntime(
      ModuleRegistry registry,
      ModuleResolver resolver,
      ModuleLayer platform,
      ClassLoader interfaceLoader,
      FabricServiceRegistry fabricRegistry,
      ControlChannelClient channel,
      InstanceIdentityRegistry identityRegistry,
      InstanceLogCloser instanceLogCloser,
      Set<ModuleId> activeModules,
      ControlPlaneRelay relay,
      String nodeId) {
    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> handleLifecycleEvent(event, runtimeRef, activeModules, channel, identityRegistry);
    // Never closed: its two background threads are daemon/virtual, so they never hold the JVM
    // open past a real shutdown, and this worker's own module churn is exactly what it needs to
    // watch for the process's whole lifetime, not just some bounded window within it.
    LeakTracker leakTracker =
        new LeakTracker(
            LEAK_DETECTION_WINDOW,
            detected ->
                log.warn(
                    "classloader leak detected: module {} survived {} past its own undeploy{}",
                    detected.id(),
                    detected.survivalTime(),
                    detected
                        .retainingPath()
                        .map(path -> "; retaining path: " + path)
                        .orElse(" (no retaining path attributed)")));
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            interfaceLoader,
            Duration.ofSeconds(5),
            sink,
            leakTracker::track,
            fabricRegistry,
            relayClient(relay),
            id ->
                identityRegistry
                    .lookup(id)
                    .map(
                        identity ->
                            new ModuleContext.InstanceInfo(
                                identity.deploymentName(),
                                identity.instanceIndex(),
                                nodeId,
                                identity.tenantId())));
    // How many virtual threads a module's BoundedModuleScheduler runs concurrently by default,
    // and the liveness/readiness probe cadence every module is checked against once ACTIVE.
    int defaultMaxConcurrency = 4;
    Duration probeInterval = Duration.ofSeconds(1);
    Duration probeTimeout = Duration.ofSeconds(2);
    int livenessFailureThreshold = 3;
    WorkerRuntime runtime =
        new WorkerRuntime(
            controller,
            registry,
            fabricRegistry,
            defaultMaxConcurrency,
            probeInterval,
            probeTimeout,
            livenessFailureThreshold,
            id -> log.error("module {} exhausted its restart budget; awaiting worker restart", id),
            identityRegistry,
            identity ->
                instanceLogCloser.closeInstance(
                    identity.deploymentName(), identity.instanceIndex()));
    runtimeRef.set(runtime);
    return new ControllerAndRuntime(controller, runtime);
  }

  /**
   * Starts every background thread a worker keeps running for its whole lifetime: the fabric TLS
   * cert-rotation watcher, the autoscaling-facing per-module metrics reporter, and the Muninn
   * NDJSON relay -- none of these ever join back into {@link #main}'s own thread.
   */
  private static void startBackgroundWork(
      FabricServer fabricServer,
      ControlChannelClient channel,
      String workerId,
      Set<ModuleId> activeModules,
      WorkerMetrics workerMetrics,
      WorkerRuntime runtime) {
    FabricServerTlsWatcher tlsWatcher = new FabricServerTlsWatcher();
    tlsWatcher.start(fabricServer, Duration.ofSeconds(5));
    Thread.ofVirtual()
        .name("gimle-metrics-reporter")
        .start(() -> metricsReportLoop(channel, activeModules, workerMetrics, runtime));
    Thread.ofVirtual()
        .name("gimle-muninn-metrics-relay")
        .start(() -> muninnMetricsRelayLoop(channel, workerId, workerMetrics));
  }

  /**
   * The {@link ModuleController}/{@link WorkerRuntime} pair {@link #buildControllerAndRuntime}
   * wires together.
   */
  private record ControllerAndRuntime(ModuleController controller, WorkerRuntime runtime) {}

  /**
   * The {@code ModuleController}/{@code WorkerRuntime} lifecycle sink: relays every state
   * transition to the agent, keeps {@code activeModules} (what {@link #metricsReportLoop} reports
   * against) in sync, and emits a durable {@link InstanceEvent} once an identity is registered.
   */
  private static void handleLifecycleEvent(
      LifecycleEvent event,
      AtomicReference<WorkerRuntime> runtimeRef,
      Set<ModuleId> activeModules,
      ControlChannelClient channel,
      InstanceIdentityRegistry identityRegistry) {
    // Reported before runtimeRef's own reaction below runs, not after: WorkerRuntime#onActive can
    // itself synchronously force a further transition (a probe class that fails to load drives the
    // module straight to FAILED before onLifecycleEvent returns), which recurses back into this
    // same method for that transition's own event. Sending this event's messages first guarantees
    // a nested FAILED report is sent -- and therefore observed by the agent -- strictly after this
    // one, instead of this event's now-stale "ACTIVE" send landing second and silently overwriting
    // the agent's already-correct "FAILED" view.
    sendQuietly(channel, new ControlMessage.ModuleStateChanged(event.id(), stateName(event)));
    identityRegistry
        .lookup(event.id())
        .ifPresent(
            identity ->
                sendQuietly(
                    channel,
                    new ControlMessage.InstanceEventOccurred(instanceEventFor(event, identity))));
    if (event instanceof LifecycleEvent.Active active) {
      activeModules.add(active.id());
    } else if (event instanceof LifecycleEvent.Uninstalled uninstalled) {
      activeModules.remove(uninstalled.id());
    } else if (event instanceof LifecycleEvent.Completed completed) {
      // A COMPLETED Job stops accumulating metricsReportLoop's per-tick CPU/memory report -- it's
      // done, not still running work worth reporting, the same reasoning Uninstalled already gets
      // above.
      activeModules.remove(completed.id());
    }
    runtimeRef.get().onLifecycleEvent(event);
  }

  private static final Duration METRICS_REPORT_INTERVAL = Duration.ofSeconds(5);

  /**
   * Self-reports this worker JVM's own process CPU and heap usage via portable {@code
   * java.lang.management} APIs -- no cgroup reads, no FFM, identical on Linux/macOS/Windows,
   * matching {@code PortableJvmFlagsResourceLimiter}'s own portability bar -- once per {@link
   * #METRICS_REPORT_INTERVAL}, against every module currently ACTIVE in this worker. One JVM-wide
   * figure reported per module rather than a true per-module breakdown: a reasonable approximation
   * under Tier 1 density packing (several modules genuinely sharing this worker JVM), not just a
   * placeholder for a since-closed gap. Feeds {@code AutoscaleReconciler}'s CPU-utilization math,
   * which previously always saw zero since nothing on this side ever sent a {@code MetricsReport}
   * at all.
   *
   * <p>Request/error rate comes from {@code workerMetrics}' cumulative counters, diffed against the
   * previous tick's reading and divided by the interval -- {@code WorkerMetrics} itself only
   * exposes running totals (Micrometer counters never go down), so computing a rate is this loop's
   * job, not the metrics registry's. A module's first tick after going ACTIVE has no prior reading
   * to diff against and reports {@code 0} rather than a spurious spike from "0 to whatever it's
   * accumulated since startup." Queue depth comes straight from that module's own {@code
   * BoundedModuleScheduler}, when one exists yet (it doesn't during the brief window between a
   * module going ACTIVE and {@code WorkerRuntime} finishing wiring its scheduler). {@code ports}
   * carries whatever that module's own hook code has reported via {@code ModuleContext#reportPort}
   * -- empty for the overwhelming majority of modules, which never call it.
   */
  private static void metricsReportLoop(
      ControlChannelClient channel,
      Set<ModuleId> activeModules,
      WorkerMetrics workerMetrics,
      WorkerRuntime runtime) {
    com.sun.management.OperatingSystemMXBean osBean =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    Runtime jvmRuntime = Runtime.getRuntime();
    Map<ModuleId, Double> lastRequestCount = new ConcurrentHashMap<>();
    Map<ModuleId, Double> lastErrorCount = new ConcurrentHashMap<>();
    double intervalSeconds = METRICS_REPORT_INTERVAL.toMillis() / 1000.0;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(METRICS_REPORT_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        double cpuLoad = osBean.getProcessCpuLoad();
        long cpuMillicoresUsed =
            cpuLoad < 0 ? 0 : Math.round(cpuLoad * osBean.getAvailableProcessors() * 1000);
        long memoryBytesUsed = jvmRuntime.totalMemory() - jvmRuntime.freeMemory();
        for (ModuleId id : activeModules) {
          double requestCount = workerMetrics.requestCount(id);
          double errorCount = workerMetrics.errorCount(id);
          double requestRatePerSecond =
              rateSince(lastRequestCount.put(id, requestCount), requestCount, intervalSeconds);
          double errorRatePerSecond =
              rateSince(lastErrorCount.put(id, errorCount), errorCount, intervalSeconds);
          int queueDepth =
              runtime.schedulerFor(id).map(BoundedModuleScheduler::queuedCount).orElse(0);
          sendQuietly(
              channel,
              new ControlMessage.MetricsReport(
                  id,
                  cpuMillicoresUsed,
                  memoryBytesUsed,
                  requestRatePerSecond,
                  queueDepth,
                  errorRatePerSecond,
                  runtime.reportedPortsFor(id)));
        }
      } catch (RuntimeException e) {
        log.warn("metrics report tick failed", e);
      }
    }
  }

  /** {@code null} previous means this module's first tick -- report 0 rather than a false spike. */
  private static double rateSince(Double previous, double current, double intervalSeconds) {
    return previous == null ? 0.0 : Math.max(0.0, current - previous) / intervalSeconds;
  }

  /**
   * Ships this worker JVM's own {@code WorkerMetrics} registry to the agent as one NDJSON snapshot
   * per tick -- one shipper's worth of data per worker process, not per module the way {@link
   * #metricsReportLoop}'s autoscaling-facing {@code MetricsReport} is; {@code MeterSnapshotCodec}
   * already tags every meter by its own module internally, so nothing here needs to iterate {@code
   * activeModules}. Deliberately a separate loop/thread from {@link #metricsReportLoop}, not a
   * shared tick: the two report different things to different consumers (autoscaling signal vs.
   * observability export payload) and conflating them would make both harder to reason about, the
   * same split {@code ControlMessage.MetricsReport}'s own javadoc draws against {@code
   * MetricsSnapshot}.
   */
  private static void muninnMetricsRelayLoop(
      ControlChannelClient channel, String workerId, WorkerMetrics workerMetrics) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(MUNINN_SHIP_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        String body = MeterSnapshotCodec.toNdjson(workerMetrics.registry());
        if (!body.isEmpty()) {
          sendQuietly(channel, new ControlMessage.MetricsSnapshot(workerId, body));
        }
      } catch (RuntimeException e) {
        log.warn("muninn metrics relay tick failed", e);
      }
    }
  }

  /** Binds the two fabric listeners a worker always offers: same-machine UDS, cross-machine TCP. */
  private static FabricBinding bindFabricServer(
      ServiceRegistry localRegistry,
      ClassLoader interfaceLoader,
      ModuleController controller,
      WorkerRuntime runtime,
      WorkerMetrics metrics,
      ModuleRegistry registry,
      Optional<String> tenantId,
      InstanceIdentityRegistry identityRegistry)
      throws IOException {
    // Forwarded by AgentMain's stableWorkerFlags as an explicit -D flag on every worker it spawns
    // (see FabricServer.DEFAULT_MAX_CONNECTIONS for the value used when this is somehow absent,
    // e.g. a worker launched by hand outside the agent).
    int maxFabricConnections =
        Integer.parseInt(System.getProperty("gimle.fabric.maxConnections", "512"));
    FabricServer server =
        new FabricServer(
            localRegistry,
            interfaceLoader,
            controller::context,
            id -> runtime.schedulerFor(id).map(scheduler -> scheduler::submit),
            Optional.of(metrics),
            owner -> registry.artifact(owner).descriptor().exports(),
            tenantId,
            id -> identityRegistry.lookup(id).map(InstanceIdentity::deploymentName),
            maxFabricConnections);
    Path udsPath = Files.createTempDirectory("gimle-fabric-uds-").resolve("f.sock");
    server.listen(UnixDomainSocketAddress.of(udsPath));
    InetSocketAddress bound = (InetSocketAddress) server.listen(new InetSocketAddress(0));
    String advertisedHost = resolveAdvertisedHost();
    InetSocketAddress advertised = new InetSocketAddress(advertisedHost, bound.getPort());
    return new FabricBinding(server, new FabricEndpoints(udsPath.toString(), advertised));
  }

  private static String resolveAdvertisedHost() {
    try {
      return InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException e) {
      // A deployment concern independent of the fabric protocol itself (real multi-homed/NAT'd
      // hosts need real address configuration); loopback keeps single-machine setups working.
      return "127.0.0.1";
    }
  }

  private static void handle(
      ControlMessage message,
      ModuleRegistry registry,
      ModuleController controller,
      ControlChannelClient channel,
      ServiceCatalog catalog,
      InstanceIdentityRegistry identityRegistry,
      Optional<String> tenantId,
      String workerId,
      WorkerMetrics workerMetrics,
      ControlPlaneRelay relay,
      FabricServer fabricServer)
      throws IOException {
    switch (message) {
      case ControlMessage.InstallModule m -> {
        try {
          ModuleArtifact artifact = ModuleArtifactReader.read(Path.of(m.artifactPath()));
          ModuleId id = registry.register(artifact);
          if (!m.deploymentName().isBlank()) {
            identityRegistry.register(
                id, new InstanceIdentity(m.deploymentName(), m.instanceIndex(), tenantId));
          }
          channel.send(new ControlMessage.ModuleStateChanged(id, "INSTALLED"));
          channel.send(new ControlMessage.Ack(m.correlationId()));
        } catch (RuntimeException e) {
          channel.send(new ControlMessage.Nack(m.correlationId(), String.valueOf(e.getMessage())));
        }
      }
      case ControlMessage.RenameInstance m -> {
        // Overwrites this ModuleId's InstanceIdentityRegistry entry in place -- the same
        // register() call InstallModule's own case above makes when it has a deployment name,
        // just with a new instanceIndex and made unconditionally here (a rename always targets an
        // already-identified instance, so there's no "blank name" case to guard against).
        // mdcTagsFor reads the registry live on every command, so this instance's logging picks
        // up the new identity on its very next tagged line with no other propagation needed; the
        // module itself is never touched (no resolve/start/stop), matching this message's whole
        // point -- retarget, don't restart.
        identityRegistry.register(
            m.id(), new InstanceIdentity(m.deploymentName(), m.instanceIndex(), tenantId));
        channel.send(new ControlMessage.Ack(m.correlationId()));
      }
      case ControlMessage.ResolveModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () ->
                  controller.resolve(
                      m.id(),
                      m.dataDirectories().entrySet().stream()
                          .collect(
                              Collectors.toMap(
                                  Map.Entry::getKey, entry -> Path.of(entry.getValue())))));
      case ControlMessage.StartModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () -> controller.start(m.id()));
      case ControlMessage.StopModule m -> {
        runCommand(
            m.correlationId(),
            channel,
            mdcTagsFor(m.id(), identityRegistry),
            () -> controller.stop(m.id()));
        // A well-behaved log appender flushes remaining lines before closing --
        // this instance may be a Job run torn down moments after completing, with no guarantee the
        // next MUNINN_SHIP_INTERVAL tick ever fires before the worker process exits. One extra
        // best-effort snapshot per StopModule, not gated on "is this the worker's last instance":
        // simpler than tracking that, and harmless -- an empty/near-empty registry snapshot costs
        // little to ship early.
        String body = MeterSnapshotCodec.toNdjson(workerMetrics.registry());
        if (!body.isEmpty()) {
          sendQuietly(channel, new ControlMessage.MetricsSnapshot(workerId, body));
        }
        GimleTracing.flush();
      }
      case ControlMessage.UninstallModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () -> controller.uninstall(m.id()));
      case ControlMessage.Ping m -> channel.send(new ControlMessage.Pong(m.correlationId()));
      case ControlMessage.CatalogUpdate m ->
          catalog.applyExternalUpdate(
              m.nodeId(),
              m.workerId(),
              m.moduleId(),
              m.export(),
              m.version(),
              m.present(),
              m.udsPath().isEmpty() ? Optional.empty() : Optional.of(m.udsPath()),
              new InetSocketAddress(m.tcpHost(), m.tcpPort()));
      case ControlMessage.ConfigDelivered m -> controller.deliverConfig(m.key(), m.value());
      case ControlMessage.NetworkPoliciesUpdated m -> fabricServer.updateNetworkPolicies(m.rules());
      case ControlMessage.RelayControlPlaneResult m -> relay.complete(m);
      default -> log.warn("unexpected control message from agent: {}", message);
    }
  }

  /**
   * The MDC tags a hosted module's own instance would carry if it logged synchronously right now --
   * computed once per command dispatch (not cached) since identity registration can happen after a
   * module is first installed. Empty for a module with no registered {@link InstanceIdentity}
   * (matches {@link InstanceTaggingServiceRegistry}'s own "degrade, don't fail" posture for the
   * same case).
   */
  private static Map<String, String> mdcTagsFor(
      ModuleId id, InstanceIdentityRegistry identityRegistry) {
    return identityRegistry
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
  }

  private static void runCommand(
      String correlationId,
      ControlChannelClient channel,
      Map<String, String> mdcTags,
      Runnable action)
      throws IOException {
    try {
      // ModuleController invokes the module's own lifecycle hooks (onInstall/onStart/onStop/
      // onUninstall) synchronously from this call -- tagging around it here is what lets a hook's
      // own logging land in this instance's own log (APPLICATION category, per-instance file)
      // instead of the worker's shared platform log; see InstanceMdcKeys.
      InstanceMdcContext.runTagged(
          mdcTags,
          () -> {
            action.run();
            return null;
          });
      channel.send(new ControlMessage.Ack(correlationId));
    } catch (RuntimeException e) {
      channel.send(new ControlMessage.Nack(correlationId, String.valueOf(e.getMessage())));
    } catch (Exception e) {
      throw new IllegalStateException("unexpected checked exception from a Runnable", e);
    }
  }

  private static String stateName(LifecycleEvent event) {
    return switch (event) {
      case LifecycleEvent.Installed ignored -> "INSTALLED";
      case LifecycleEvent.Resolved ignored -> "RESOLVED";
      case LifecycleEvent.Starting ignored -> "STARTING";
      case LifecycleEvent.Active ignored -> "ACTIVE";
      case LifecycleEvent.Stopping ignored -> "STOPPING";
      case LifecycleEvent.Uninstalled ignored -> "UNINSTALLED";
      case LifecycleEvent.TransitionFailed ignored -> "FAILED";
      case LifecycleEvent.Completed ignored -> "COMPLETED";
    };
  }

  /**
   * Builds the durable {@link InstanceEvent} counterpart to a {@link LifecycleEvent} -- only called
   * once an {@link InstanceIdentity} is registered for the module (matching {@link #mdcTagsFor}'s
   * own "no identity yet, skip" posture), since an event with no deployment/index to attach to has
   * nowhere durable to live. A fresh {@code id} per event gives {@code gimle-cli events}/the
   * console's events panel a stable pagination key independent of storage order.
   */
  // Package-visible (no `private`), not for production reuse but so WorkerMainTest can exercise
  // this pure mapping directly rather than only indirectly through a full worker process.
  static InstanceEvent instanceEventFor(LifecycleEvent event, InstanceIdentity identity) {
    long occurredAtEpochMilli = event.at().toEpochMilli();
    String id = UUID.randomUUID().toString();
    return switch (event) {
      case LifecycleEvent.Installed ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.INSTALLED,
              "module installed",
              occurredAtEpochMilli);
      case LifecycleEvent.Resolved ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.RESOLVED,
              "module resolved",
              occurredAtEpochMilli);
      case LifecycleEvent.Starting ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.STARTING,
              "module starting",
              occurredAtEpochMilli);
      case LifecycleEvent.Active ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.ACTIVE,
              "module active",
              occurredAtEpochMilli);
      case LifecycleEvent.Stopping stopping ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.STOPPING,
              "module stopping, drain deadline " + stopping.deadline(),
              occurredAtEpochMilli);
      case LifecycleEvent.Uninstalled ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.UNINSTALLED,
              "module uninstalled",
              occurredAtEpochMilli);
      case LifecycleEvent.TransitionFailed failed ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.TRANSITION_FAILED,
              "transition " + failed.from() + " -> " + failed.to() + " failed",
              Optional.of(transitionFailureDetail(failed.cause())),
              occurredAtEpochMilli);
      case LifecycleEvent.Completed ignored ->
          new InstanceEvent(
              id,
              identity.deploymentName(),
              identity.instanceIndex(),
              InstanceEventKind.COMPLETED,
              "job run completed successfully",
              occurredAtEpochMilli);
    };
  }

  /**
   * Names the real cause of a failed lifecycle transition, not just {@link
   * GimleLifecycleException#hookFailed}'s own generic wrapper text -- a module's real, well-typed
   * exception (e.g. naming exactly which config key is missing) previously never reached {@code
   * gimle logs}/{@code gimle events}/the console at all, since this detail string used to report
   * only the wrapper's own class and message. {@code cause} is unwrapped one level when it's a
   * {@link GimleLifecycleException} with a cause of its own (a hook-invocation failure always is
   * one; {@code illegalTransition} never has a cause, so it falls through unchanged below).
   */
  static String transitionFailureDetail(Throwable cause) {
    if (!(cause instanceof GimleLifecycleException) || cause.getCause() == null) {
      return cause.getClass().getName() + ": " + cause.getMessage();
    }
    Throwable realCause = cause.getCause();
    return cause.getMessage()
        + ": "
        + realCause.getClass().getSimpleName()
        + ": "
        + realCause.getMessage();
  }

  private static void sendQuietly(ControlChannelClient channel, ControlMessage message) {
    try {
      channel.send(message);
    } catch (IOException e) {
      log.warn("failed to send {} over control channel: {}", message, e.getMessage());
    }
  }

  private record FabricEndpoints(String udsPath, InetSocketAddress tcpAddress) {}

  /**
   * Threads the {@link FabricServer} instance itself out of {@link #bindFabricServer} alongside the
   * endpoints it already returned -- needed so {@link #main} can hand it to a {@link
   * FabricServerTlsWatcher}; previously, nothing past {@code bindFabricServer} ever needed to hold
   * a reference to the server itself.
   */
  private record FabricBinding(FabricServer server, FabricEndpoints endpoints) {}
}
