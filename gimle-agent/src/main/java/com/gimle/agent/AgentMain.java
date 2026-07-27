package com.gimle.agent;

import com.gimle.core.exception.GimleIsolationException;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import com.gimle.fabric.catalog.ServiceCatalog;
import com.gimle.fabric.cluster.GossipConfig;
import com.gimle.fabric.cluster.GossipMember;
import com.gimle.fabric.cluster.MemberId;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node agent's entry point. Registers with the control plane once, then loops forever: polls
 * {@code GET /nodes/{nodeId}/assignments} and reconciles the locally-supervised {@link
 * WorkerProcessSupervisor} set against it (spawning a worker JVM per newly-assigned instance,
 * tearing one down per instance no longer assigned -- each replica gets its own worker JVM,
 * matching the scheduler's anti-affinity assumption), then reports a heartbeat.
 *
 * <p>Independent of that control-plane loop, this agent also runs a {@link GossipMember} (SWIM
 * membership over UDP, joined via {@code seeds}) carrying a {@link ServiceCatalog} on its gossip
 * piggyback channel: it folds {@code ServiceRegistered}/{@code ServiceUnregistered} reports from
 * its own supervised workers into the catalog, and relays every genuinely new delta -- local or
 * learned from gossip about a remote node -- back down to every supervised worker as a {@code
 * CatalogUpdate}, so each worker's own {@code FabricServiceRegistry} stays eventually consistent
 * without ever querying a central catalog service.
 */
public final class AgentMain {

  private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
  private static final Duration TICK_INTERVAL = Duration.ofSeconds(5);
  private static final AtomicLong CORRELATION_COUNTER = new AtomicLong();

  /**
   * Enables the retaining-path attribution {@code OldObjectSampleCorrelator} (gimle-module) can
   * only surface when the worker JVM itself was launched with {@code path-to-gc-roots=true} -- that
   * setting is a recording-launch option, not something settable through the in-process {@code
   * RecordingStream} API a worker's own leak detector uses. Always-on, not tied to any {@code
   * ResourceSpec}: it's an observability concern every worker JVM needs regardless of its isolation
   * tier.
   */
  private static final String LEAK_DETECTION_JFR_FLAG =
      "-XX:StartFlightRecording:name=gimle-leak-detection,disk=false,settings=profile,path-to-gc-roots=true";

  private AgentMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 5) {
      System.err.println(
          "usage: AgentMain <nodeId> <controlPlaneBaseUrl> <gossipBindHost:port>"
              + " <seeds(host:port,host:port|-)> <javaExecutable> <worker-command-tail...>");
      System.exit(2);
      return;
    }
    String nodeId = args[0];
    URI baseUrl = URI.create(args[1]);
    InetSocketAddress gossipBindAddress = parseHostPort(args[2]);
    List<InetSocketAddress> seeds = parseSeeds(args[3]);
    String javaExecutable = args[4];
    List<String> commandTail = List.of(args).subList(5, args.length);

    System.setProperty("gimle.process.role", "AGENT");
    System.setProperty("gimle.node.id", nodeId);
    Path logRoot = Path.of(System.getProperty("gimle.log.root", "gimle-logs"));
    GimleLogging.attachPlatformFileAppender(logRoot.resolve("agent-platform.log"));

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    CapacityTracker capacityTracker = CapacityTracker.ofThisMachine();
    HttpClient httpClient = HttpClient.newHttpClient();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();

    MemberId self = new MemberId(nodeId, gossipBindAddress);
    GossipMember gossipMember = new GossipMember(self, GossipConfig.defaults());
    ServiceCatalog catalog = new ServiceCatalog();
    gossipMember.attachCatalog(catalog);
    catalog.onDelta(delta -> relayCatalogDelta(delta, supervised));
    gossipMember.start();
    gossipMember.join(seeds);
    log.info("agent {} gossip member listening at {}", nodeId, gossipMember.self().gossipAddress());

    register(httpClient, baseUrl, nodeId, resourceLimiter);
    log.info("agent {} registered with control plane at {}", nodeId, baseUrl);

    while (!Thread.currentThread().isInterrupted()) {
      try {
        reconcileAssignments(
            httpClient,
            baseUrl,
            nodeId,
            supervised,
            javaExecutable,
            commandTail,
            resourceLimiter,
            capacityTracker,
            gossipMember,
            catalog,
            logRoot);
        sendHeartbeat(httpClient, baseUrl, nodeId, supervised, capacityTracker);
      } catch (RuntimeException | IOException e) {
        log.error("agent tick failed: {}", e.getMessage(), e);
      }
      Thread.sleep(TICK_INTERVAL.toMillis());
    }
  }

  private static InetSocketAddress parseHostPort(String text) {
    int at = text.lastIndexOf(':');
    if (at < 0) {
      throw new IllegalArgumentException("expected host:port, got: " + text);
    }
    return new InetSocketAddress(text.substring(0, at), Integer.parseInt(text.substring(at + 1)));
  }

  private static List<InetSocketAddress> parseSeeds(String text) {
    if (text.equals("-") || text.isBlank()) {
      return List.of();
    }
    List<InetSocketAddress> seeds = new ArrayList<>();
    for (String entry : text.split(",")) {
      seeds.add(parseHostPort(entry));
    }
    return seeds;
  }

  /**
   * Relays a newly-applied catalog delta -- local or gossip-learned -- to every supervised worker's
   * own locally-cached catalog.
   */
  private static void relayCatalogDelta(
      com.gimle.fabric.catalog.CatalogDelta delta, Map<String, SupervisedInstance> supervised) {
    ControlMessage update = toCatalogUpdate(delta);
    for (SupervisedInstance instance : supervised.values()) {
      WorkerConnection connection = instance.connection;
      if (connection != null) {
        try {
          connection.send(update);
        } catch (IOException e) {
          log.warn("failed to relay catalog update to a supervised worker: {}", e.getMessage());
        }
      }
    }
  }

  // ---- control-plane registration/heartbeat/assignment fetch ----

  private static void register(
      HttpClient httpClient, URI baseUrl, String nodeId, ResourceLimiter resourceLimiter)
      throws IOException, InterruptedException {
    Set<IsolationTier> supportedTiers = new LinkedHashSet<>();
    for (IsolationTier tier : IsolationTier.values()) {
      if (resourceLimiter.supports(tier)) {
        supportedTiers.add(tier);
      }
    }
    Map<String, Object> capabilities = new LinkedHashMap<>();
    capabilities.put("supportedTiers", supportedTiers.stream().map(Enum::name).toList());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("capabilities", capabilities);

    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/register"))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static void sendHeartbeat(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      CapacityTracker capacityTracker)
      throws IOException, InterruptedException {
    CapacityTracker.Snapshot snapshot = capacityTracker.snapshot();
    Map<String, Object> capacity = new LinkedHashMap<>();
    capacity.put("totalMemoryBytes", snapshot.totalMemoryBytes());
    capacity.put("assignedMemoryBytes", snapshot.assignedMemoryBytes());
    capacity.put("totalCpuMillicores", snapshot.totalCpuMillicores());
    capacity.put("assignedCpuMillicores", snapshot.assignedCpuMillicores());

    List<Map<String, Object>> instances = new ArrayList<>();
    for (SupervisedInstance instance : supervised.values()) {
      instances.add(observationJson(instance));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("capacity", capacity);
    body.put("instances", instances);

    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/heartbeat"))
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private static Map<String, Object> observationJson(SupervisedInstance instance) {
    String state = instance.lifecycleState;
    boolean alive = !"FAILED".equals(state);
    boolean ready = "ACTIVE".equals(state);

    Map<String, Object> moduleId = new LinkedHashMap<>();
    moduleId.put("name", instance.assigned.moduleId().name());
    moduleId.put("version", instance.assigned.moduleId().version().toString());

    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("deploymentName", instance.assigned.deploymentName());
    observation.put("instanceIndex", instance.assigned.instanceIndex());
    observation.put("moduleId", moduleId);
    observation.put("lifecycleState", state);
    observation.put("alive", alive);
    observation.put("ready", ready);
    return observation;
  }

  private static List<AssignedInstance> fetchAssignments(
      HttpClient httpClient, URI baseUrl, String nodeId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/assignments")).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<AssignedInstance> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = Json.asObject(entry);
      Map<String, Object> moduleIdMap = Json.asObject(map.get("moduleId"));
      ModuleId moduleId =
          new ModuleId(
              (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
      Object tenantId = map.get("tenantId");
      result.add(
          new AssignedInstance(
              (String) map.get("deploymentName"),
              ((Number) map.get("instanceIndex")).intValue(),
              moduleId,
              (String) map.get("artifactPath"),
              tenantId == null ? Optional.empty() : Optional.of((String) tenantId)));
    }
    return result;
  }

  /**
   * Fetches this tenant's entire tenant-scoped config/secret set, already decrypted server-side:
   * {@code GET /config/{tenantId}} returns every {@code ConfigEntry} for that tenant as plaintext,
   * since the control plane alone holds the secrets key file.
   */
  private static List<ConfigValue> fetchConfigForTenant(
      HttpClient httpClient, URI baseUrl, String tenantId)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/config/" + tenantId)).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    List<Object> raw = Json.asArray(Json.parse(response.body()));
    List<ConfigValue> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = Json.asObject(entry);
      result.add(
          new ConfigValue(
              (String) map.get("key"),
              (String) map.get("value"),
              Boolean.TRUE.equals(map.get("encrypted"))));
    }
    return result;
  }

  private record ConfigValue(String key, String value, boolean wasEncrypted) {}

  // ---- reconciling the locally-supervised set against the control plane's assignments ----

  private static void reconcileAssignments(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      Path logRoot)
      throws IOException, InterruptedException {
    List<AssignedInstance> assignments = fetchAssignments(httpClient, baseUrl, nodeId);
    Set<String> currentKeys = new LinkedHashSet<>();
    for (AssignedInstance assigned : assignments) {
      String key = instanceKey(assigned);
      currentKeys.add(key);
      if (!supervised.containsKey(key)) {
        try {
          startInstance(
              assigned,
              key,
              supervised,
              nodeId,
              javaExecutable,
              commandTail,
              resourceLimiter,
              capacityTracker,
              gossipMember,
              catalog,
              httpClient,
              baseUrl,
              logRoot);
        } catch (IOException | RuntimeException e) {
          log.error("failed to start instance {}: {}", key, e.getMessage(), e);
        }
      }
    }
    for (String key : List.copyOf(supervised.keySet())) {
      if (!currentKeys.contains(key)) {
        stopInstance(key, supervised, capacityTracker);
      }
    }
  }

  private static void startInstance(
      AssignedInstance assigned,
      String key,
      Map<String, SupervisedInstance> supervised,
      String nodeId,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl,
      Path logRoot)
      throws IOException {
    ModuleDescriptor descriptor =
        ModuleArtifactReader.read(Path.of(assigned.artifactPath())).descriptor();
    if (!resourceLimiter.supports(descriptor.isolationTier())) {
      throw GimleIsolationException.tierUnsupported(
          assigned.moduleId(), descriptor.isolationTier());
    }

    Path socketPath = Files.createTempDirectory("gimle-worker-uds-").resolve("c.sock");
    ControlChannelServer server = new ControlChannelServer(socketPath);
    ResourceLimitHandle handle = resourceLimiter.prepare(key, descriptor.resourceRequest());
    List<String> baseCommand = new ArrayList<>();
    baseCommand.add(javaExecutable);
    baseCommand.add(LEAK_DETECTION_JFR_FLAG);
    baseCommand.addAll(resourceLimiter.jvmFlags(handle));
    baseCommand.addAll(commandTail);
    baseCommand.add(nodeId);
    // WorkerMain expects <nodeId> <tenantId-or-empty> <control-socket-path>, in that order;
    // WorkerProcessSupervisor always appends the control-socket path last, so tenantId must be
    // appended here, right after nodeId, not after the fact.
    baseCommand.add(assigned.tenantId().orElse(""));

    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));
    Path systemLogFile = logRoot.resolve("workers").resolve(key + "-system.log");
    WorkerProcessSupervisor supervisor =
        new WorkerProcessSupervisor(
            key,
            baseCommand,
            socketPath,
            restartTracker,
            exhaustedKey -> {
              log.error(
                  "instance {} exhausted its restart budget on this node; giving up locally",
                  exhaustedKey);
              resourceLimiter.release(handle);
              capacityTracker.release(exhaustedKey);
              supervised.remove(exhaustedKey);
            },
            Optional.of(systemLogFile));

    SupervisedInstance instance = new SupervisedInstance(assigned, supervisor, server, descriptor);
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    supervisor.start();

    Thread.ofVirtual()
        .name("gimle-instance-starter-" + key)
        .start(() -> driveInstanceUp(instance, key, gossipMember, catalog, httpClient, baseUrl));
  }

  private static void driveInstanceUp(
      SupervisedInstance instance,
      String key,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      HttpClient httpClient,
      URI baseUrl) {
    try {
      WorkerConnection connection = instance.server.accept();
      instance.connection = connection;
      Thread.ofVirtual()
          .name("gimle-instance-reader-" + key)
          .start(() -> readLoop(instance, key, gossipMember, catalog));

      connection.send(
          new ControlMessage.InstallModule(
              nextCorrelationId(),
              instance.assigned.artifactPath(),
              instance.assigned.deploymentName(),
              instance.assigned.instanceIndex()));
      connection.send(
          new ControlMessage.ResolveModule(nextCorrelationId(), instance.assigned.moduleId()));
      // Delivered after Resolve (which is when the worker's ModuleContext is created) and before
      // Start, over this same ordered channel, so every module hook's config(key) lookups are
      // already backed by real values from the moment it starts.
      deliverConfig(instance, connection, httpClient, baseUrl);
      connection.send(
          new ControlMessage.StartModule(nextCorrelationId(), instance.assigned.moduleId()));
    } catch (IOException e) {
      log.error("failed to bring up instance {}: {}", key, e.getMessage());
    }
  }

  private static void deliverConfig(
      SupervisedInstance instance, WorkerConnection connection, HttpClient httpClient, URI baseUrl)
      throws IOException {
    Optional<String> tenantId = instance.assigned.tenantId();
    if (tenantId.isEmpty()) {
      return;
    }
    List<ConfigValue> entries;
    try {
      entries = fetchConfigForTenant(httpClient, baseUrl, tenantId.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (RuntimeException e) {
      log.warn(
          "failed to fetch config for tenant {}: {}; instance will start without it",
          tenantId.get(),
          e.getMessage());
      return;
    }
    for (ConfigValue entry : entries) {
      connection.send(
          new ControlMessage.ConfigDelivered(entry.key(), entry.value(), entry.wasEncrypted()));
    }
  }

  private static void readLoop(
      SupervisedInstance instance, String key, GossipMember gossipMember, ServiceCatalog catalog) {
    try {
      Optional<ControlMessage> received;
      while ((received = instance.connection.receive()).isPresent()) {
        ControlMessage message = received.get();
        if (message instanceof ControlMessage.ModuleStateChanged changed) {
          instance.lifecycleState = changed.state();
        } else if (message instanceof ControlMessage.Nack nack) {
          log.warn("instance {} nacked {}: {}", key, nack.correlationId(), nack.reason());
        } else if (message instanceof ControlMessage.Hello hello) {
          instance.fabricWorkerId = hello.workerId();
          instance.fabricUdsPath = hello.fabricUdsPath();
          instance.fabricTcpAddress =
              new InetSocketAddress(hello.fabricTcpHost(), hello.fabricTcpPort());
          // Sync this worker's fresh FabricServiceRegistry cache with everything this agent
          // already knows: the gossip-driven onDelta relay only fires for a delta applied *after*
          // its listener was registered, so anything learned before this worker connected would
          // otherwise never reach it.
          syncCatalogToWorker(instance, catalog);
        } else if (message instanceof ControlMessage.ServiceRegistered registered) {
          registerIntoCatalog(
              instance, gossipMember, catalog, registered.moduleId(), registered.export(), true);
        } else if (message instanceof ControlMessage.ServiceUnregistered unregistered) {
          registerIntoCatalog(
              instance,
              gossipMember,
              catalog,
              unregistered.moduleId(),
              unregistered.export(),
              false);
        }
      }
      log.info("instance {} control channel closed", key);
    } catch (IOException e) {
      log.warn("instance {} control channel failed: {}", key, e.getMessage());
    }
  }

  private static void syncCatalogToWorker(SupervisedInstance instance, ServiceCatalog catalog) {
    WorkerConnection connection = instance.connection;
    List<com.gimle.fabric.catalog.CatalogDelta> deltas = catalog.allPresentDeltas();
    log.debug("syncing {} known catalog delta(s) to a newly-connected worker", deltas.size());
    for (com.gimle.fabric.catalog.CatalogDelta delta : deltas) {
      try {
        connection.send(toCatalogUpdate(delta));
      } catch (IOException e) {
        log.warn("failed to sync catalog state to a newly-connected worker: {}", e.getMessage());
        return;
      }
    }
  }

  private static ControlMessage.CatalogUpdate toCatalogUpdate(
      com.gimle.fabric.catalog.CatalogDelta delta) {
    return new ControlMessage.CatalogUpdate(
        delta.nodeId(),
        delta.workerId(),
        delta.moduleId(),
        delta.export(),
        delta.version(),
        delta.present(),
        delta.udsPath().orElse(""),
        delta.tcpAddress().getHostString(),
        delta.tcpAddress().getPort());
  }

  private static void registerIntoCatalog(
      SupervisedInstance instance,
      GossipMember gossipMember,
      ServiceCatalog catalog,
      ModuleId moduleId,
      com.gimle.core.module.ServiceExport export,
      boolean present) {
    if (instance.fabricWorkerId == null) {
      log.warn(
          "instance reported a service export before its Hello handshake; dropping catalog update"
              + " for {}",
          moduleId);
      return;
    }
    Optional<String> udsPath =
        instance.fabricUdsPath.isEmpty() ? Optional.empty() : Optional.of(instance.fabricUdsPath);
    if (present) {
      catalog.localRegister(
          gossipMember.self(),
          instance.fabricWorkerId,
          moduleId,
          export,
          udsPath,
          instance.fabricTcpAddress);
    } else {
      catalog.localUnregister(gossipMember.self(), instance.fabricWorkerId, moduleId, export);
    }
  }

  private static void stopInstance(
      String key, Map<String, SupervisedInstance> supervised, CapacityTracker capacityTracker) {
    SupervisedInstance instance = supervised.remove(key);
    if (instance == null) {
      return;
    }
    WorkerConnection connection = instance.connection;
    if (connection != null) {
      try {
        connection.send(
            new ControlMessage.StopModule(nextCorrelationId(), instance.assigned.moduleId()));
      } catch (IOException e) {
        log.warn("failed to send StopModule to instance {}: {}", key, e.getMessage());
      }
    }
    instance.supervisor.close();
    try {
      instance.server.close();
    } catch (IOException e) {
      log.warn("failed to close control channel server for instance {}: {}", key, e.getMessage());
    }
    capacityTracker.release(key);
  }

  private static String instanceKey(AssignedInstance assigned) {
    return assigned.deploymentName() + "#" + assigned.instanceIndex();
  }

  private static String nextCorrelationId() {
    return "c" + CORRELATION_COUNTER.incrementAndGet();
  }
}
