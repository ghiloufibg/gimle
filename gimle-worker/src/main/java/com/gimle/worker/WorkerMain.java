package com.gimle.worker;

import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.InstanceLogCloser;
import com.gimle.core.logging.InstanceMdcContext;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.module.ModuleId;
import com.gimle.core.protocol.ControlMessage;
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
    FabricEndpoints fabricEndpoints = bindFabricServer(taggedLocal, interfaceLoader);
    MemberId selfNode = new MemberId(nodeId, new InetSocketAddress(0));
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
            tenantId);

    AtomicReference<WorkerRuntime> runtimeRef = new AtomicReference<>();
    Consumer<LifecycleEvent> sink =
        event -> {
          runtimeRef.get().onLifecycleEvent(event);
          sendQuietly(channel, new ControlMessage.ModuleStateChanged(event.id(), stateName(event)));
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

  /** Binds the two fabric listeners a worker always offers: same-machine UDS, cross-machine TCP. */
  private static FabricEndpoints bindFabricServer(
      ServiceRegistry localRegistry, ClassLoader interfaceLoader) throws IOException {
    FabricServer server = new FabricServer(localRegistry, interfaceLoader);
    Path udsPath = Files.createTempDirectory("gimle-fabric-uds-").resolve("f.sock");
    server.listen(UnixDomainSocketAddress.of(udsPath));
    InetSocketAddress bound = (InetSocketAddress) server.listen(new InetSocketAddress(0));
    String advertisedHost = resolveAdvertisedHost();
    InetSocketAddress advertised = new InetSocketAddress(advertisedHost, bound.getPort());
    return new FabricEndpoints(udsPath.toString(), advertised);
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

  private static void sendQuietly(ControlChannelClient channel, ControlMessage message) {
    try {
      channel.send(message);
    } catch (IOException e) {
      log.warn("failed to send {} over control channel: {}", message, e.getMessage());
    }
  }

  private record FabricEndpoints(String udsPath, InetSocketAddress tcpAddress) {}
}
