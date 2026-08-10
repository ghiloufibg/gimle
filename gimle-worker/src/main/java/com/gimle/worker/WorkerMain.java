package com.gimle.worker;

import com.gimle.core.banner.BannerPrinter;
import com.gimle.core.banner.GimleVersion;
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
import com.gimle.module.lifecycle.LifecycleEvent;
import com.gimle.module.lifecycle.ModuleController;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import com.gimle.module.resolve.ModuleRegistry;
import com.gimle.module.resolve.ModuleResolver;
import com.gimle.observability.GimleTracing;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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

  private WorkerMain() {}

  public static void main(String[] args) throws IOException {
    // Suppressed by default when gimle-agent spawns this process (AgentMain#buildWorkerCommand
    // sets -Dgimle.banner.enabled=false unconditionally) since a worker starts once per module
    // instance rather than once per node/replica lifecycle; still prints when WorkerMain is run
    // directly (manual testing, gimle:worker-style standalone use).
    BannerPrinter.print(
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

    // Read fresh by JsonLogEncoder on every event (process-global, not thread-local, so this is
    // safe however early other threads start logging) and by the two file appenders attached
    // just below, which need the actual path now rather than at logback.xml parse time (which
    // already happened, before this line, via the CONSOLE appender).
    System.setProperty("gimle.process.role", "WORKER");
    System.setProperty("gimle.node.id", nodeId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("worker-platform.log"));
    InstanceLogCloser instanceLogCloser =
        GimleLogging.attachInstanceSiftingAppender(logRoot.resolve("instances"));

    GimleTracing.installDefault();

    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(args[2]));
    ControlChannelClient channel =
        ControlChannelClient.connectWithRetry(
            address, Duration.ofMillis(200), Duration.ofSeconds(30));
    log.info("connected to agent control socket at {}", address);

    long pid = ProcessHandle.current().pid();
    String workerId = "worker-" + pid;

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
    // P2-17: forwarded by AgentMain's buildWorkerCommand as an explicit -D flag on every worker
    // it spawns; defaults to false (today's unchanged behavior) if somehow absent, e.g. a worker
    // launched by hand outside the agent.
    boolean defaultDenyCrossTenant =
        Boolean.parseBoolean(System.getProperty("gimle.fabric.defaultDenyCrossTenant", "false"));
    FabricServiceRegistry fabricRegistry =
        new FabricServiceRegistry(
            selfNode,
            workerId,
            taggedLocal,
            catalog,
            owner -> registry.artifact(owner).descriptor().exports(),
            message -> sendQuietly(channel, message),
            interfaceLoader,
            5,
            0.5,
            Duration.ofSeconds(5),
            tenantId,
            0.5,
            defaultDenyCrossTenant);

    // Every module this worker currently has ACTIVE -- fed to the metrics-reporter loop below,
    // which has no other way to know which module ids to report against (ModuleRegistry exposes
    // no "list everything" query, only lookups by a name/id it's told).
    Set<ModuleId> activeModules = ConcurrentHashMap.newKeySet();
    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          runtimeRef.get().onLifecycleEvent(event);
          if (event instanceof LifecycleEvent.Active active) {
            activeModules.add(active.id());
          } else if (event instanceof LifecycleEvent.Uninstalled uninstalled) {
            activeModules.remove(uninstalled.id());
          }
          sendQuietly(channel, new ControlMessage.ModuleStateChanged(event.id(), stateName(event)));
          identityRegistry
              .lookup(event.id())
              .ifPresent(
                  identity ->
                      sendQuietly(
                          channel,
                          new ControlMessage.InstanceEventOccurred(
                              instanceEventFor(event, identity))));
        };
    ModuleController controller =
        new ModuleController(
            registry,
            resolver,
            platform,
            interfaceLoader,
            Duration.ofSeconds(5),
            sink,
            fabricRegistry);
    WorkerRuntime runtime =
        new WorkerRuntime(
            controller,
            registry,
            fabricRegistry,
            4,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            3,
            id -> log.error("module {} exhausted its restart budget; awaiting worker restart", id),
            identityRegistry,
            identity ->
                instanceLogCloser.closeInstance(
                    identity.deploymentName(), identity.instanceIndex()));
    runtimeRef.set(runtime);

    // Constructed only now that controller/runtime exist: FabricServer routes an inbound call's
    // actual invocation through the target module's own ModuleContext (drain-visible in-flight
    // count) and BoundedModuleScheduler (real concurrency bound, not just probe checks), both of
    // which only exist once a module has gone ACTIVE through this same controller/runtime pair.
    WorkerMetrics workerMetrics = new WorkerMetrics();
    FabricBinding fabricBinding =
        bindFabricServer(taggedLocal, interfaceLoader, controller, runtime, workerMetrics);
    FabricEndpoints fabricEndpoints = fabricBinding.endpoints();
    FabricServerTlsWatcher tlsWatcher = new FabricServerTlsWatcher();
    tlsWatcher.start(fabricBinding.server(), Duration.ofSeconds(5));
    Thread.ofVirtual()
        .name("gimle-metrics-reporter")
        .start(() -> metricsReportLoop(channel, activeModules, workerMetrics, runtime));

    channel.send(
        new ControlMessage.Hello(
            workerId,
            pid,
            fabricEndpoints.udsPath(),
            fabricEndpoints.tcpAddress().getHostString(),
            fabricEndpoints.tcpAddress().getPort()));

    Optional<ControlMessage> received;
    while ((received = channel.receive()).isPresent()) {
      handle(received.get(), registry, controller, channel, catalog, identityRegistry, tenantId);
    }
    log.info("control channel closed by agent; shutting down");
  }

  private static final Duration METRICS_REPORT_INTERVAL = Duration.ofSeconds(5);

  /**
   * Self-reports this worker JVM's own process CPU and heap usage via portable {@code
   * java.lang.management} APIs -- no cgroup reads, no FFM, identical on Linux/macOS/Windows,
   * matching {@code PortableJvmFlagsResourceLimiter}'s own portability bar -- once per {@link
   * #METRICS_REPORT_INTERVAL}, against every module currently ACTIVE in this worker. One JVM-wide
   * figure reported per module rather than a true per-module breakdown: a reasonable approximation
   * under Tier 1 density packing (several modules genuinely sharing this worker JVM, implemented
   * since P1-5), not just a placeholder for a since-closed gap. Feeds {@code AutoscaleReconciler}'s
   * CPU-utilization math, which previously always saw zero since nothing on this side ever sent a
   * {@code MetricsReport} at all.
   *
   * <p>Request/error rate comes from {@code workerMetrics}' cumulative counters, diffed against the
   * previous tick's reading and divided by the interval -- {@code WorkerMetrics} itself only
   * exposes running totals (Micrometer counters never go down), so computing a rate is this loop's
   * job, not the metrics registry's. A module's first tick after going ACTIVE has no prior reading
   * to diff against and reports {@code 0} rather than a spurious spike from "0 to whatever it's
   * accumulated since startup." Queue depth comes straight from that module's own {@code
   * BoundedModuleScheduler}, when one exists yet (it doesn't during the brief window between a
   * module going ACTIVE and {@code WorkerRuntime} finishing wiring its scheduler).
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
                errorRatePerSecond));
      }
    }
  }

  /** {@code null} previous means this module's first tick -- report 0 rather than a false spike. */
  private static double rateSince(Double previous, double current, double intervalSeconds) {
    return previous == null ? 0.0 : Math.max(0.0, current - previous) / intervalSeconds;
  }

  /** Binds the two fabric listeners a worker always offers: same-machine UDS, cross-machine TCP. */
  private static FabricBinding bindFabricServer(
      ServiceRegistry localRegistry,
      ClassLoader interfaceLoader,
      ModuleController controller,
      WorkerRuntime runtime,
      WorkerMetrics metrics)
      throws IOException {
    FabricServer server =
        new FabricServer(
            localRegistry,
            interfaceLoader,
            controller::context,
            id -> runtime.schedulerFor(id).map(scheduler -> scheduler::submit),
            Optional.of(metrics));
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
      Optional<String> tenantId)
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
      case ControlMessage.ResolveModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () -> controller.resolve(m.id()));
      case ControlMessage.StartModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () -> controller.start(m.id()));
      case ControlMessage.StopModule m ->
          runCommand(
              m.correlationId(),
              channel,
              mdcTagsFor(m.id(), identityRegistry),
              () -> controller.stop(m.id()));
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
    };
  }

  /**
   * Builds the durable {@link InstanceEvent} counterpart to a {@link LifecycleEvent} -- only called
   * once an {@link InstanceIdentity} is registered for the module (matching {@link #mdcTagsFor}'s
   * own "no identity yet, skip" posture), since an event with no deployment/index to attach to has
   * nowhere durable to live. A fresh {@code id} per event gives {@code gimle-cli events}/the
   * console's events panel a stable pagination key independent of storage order.
   */
  private static InstanceEvent instanceEventFor(LifecycleEvent event, InstanceIdentity identity) {
    long occurredAtEpochMilli = event.at().toEpochMilli();
    String id = java.util.UUID.randomUUID().toString();
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
              Optional.of(failed.cause().getClass().getName() + ": " + failed.cause().getMessage()),
              occurredAtEpochMilli);
    };
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
   * FabricServerTlsWatcher}; before §6, nothing past {@code bindFabricServer} ever needed to hold a
   * reference to the server itself.
   */
  private record FabricBinding(FabricServer server, FabricEndpoints endpoints) {}
}
