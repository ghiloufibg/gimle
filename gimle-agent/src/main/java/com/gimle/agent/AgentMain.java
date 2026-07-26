package com.gimle.agent;

import com.gimle.core.exception.GimleIsolationException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.ControlMessage;
import com.gimle.core.protocol.Json;
import com.gimle.core.restart.RestartTracker;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.os.ResourceLimitHandle;
import com.gimle.os.ResourceLimiter;
import com.gimle.os.portable.PortableJvmFlagsResourceLimiter;
import java.io.IOException;
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
 * The node agent's entry point (design §4, extended by §1/§9 for Phase 3). Registers with the
 * control plane once, then loops forever: poll {@code GET /nodes/{nodeId}/assignments} and
 * reconcile the locally-supervised {@link WorkerProcessSupervisor} set against it (spawning a
 * worker JVM per newly-assigned instance, tearing one down per instance no longer assigned -- each
 * replica gets its own worker JVM, matching the scheduler's anti-affinity assumption), then report
 * a heartbeat. This replaces Phase 2's single hardcoded worker with a dynamically-changing,
 * control-plane-driven set -- the structural gap the Phase 3 design flagged as blocking the
 * machine-level escalation path.
 *
 * <p>Per-instance liveness/readiness in the heartbeat is derived from the last {@code
 * ModuleStateChanged} lifecycle state this agent has observed from that instance's worker, not from
 * a dedicated health push (Phase 2's {@code WorkerMain} never gained one): {@code ACTIVE} is
 * reported alive+ready, {@code FAILED} is reported neither, every other state (installing,
 * resolving, starting, stopping) is reported alive-but-not-yet-ready. This is an honest, documented
 * simplification given the current wire protocol, not a claim of real probe-level fidelity.
 */
public final class AgentMain {

  private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
  private static final Duration TICK_INTERVAL = Duration.ofSeconds(5);
  private static final AtomicLong CORRELATION_COUNTER = new AtomicLong();

  private AgentMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length < 3) {
      System.err.println(
          "usage: AgentMain <nodeId> <controlPlaneBaseUrl> <javaExecutable> <worker-command-tail...>");
      System.exit(2);
      return;
    }
    String nodeId = args[0];
    URI baseUrl = URI.create(args[1]);
    String javaExecutable = args[2];
    List<String> commandTail = List.of(args).subList(3, args.length);

    ResourceLimiter resourceLimiter = new PortableJvmFlagsResourceLimiter();
    CapacityTracker capacityTracker = CapacityTracker.ofThisMachine();
    HttpClient httpClient = HttpClient.newHttpClient();
    Map<String, SupervisedInstance> supervised = new ConcurrentHashMap<>();

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
            capacityTracker);
        sendHeartbeat(httpClient, baseUrl, nodeId, supervised, capacityTracker);
      } catch (RuntimeException | IOException e) {
        log.error("agent tick failed: {}", e.getMessage(), e);
      }
      Thread.sleep(TICK_INTERVAL.toMillis());
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

  @SuppressWarnings("unchecked")
  private static List<AssignedInstance> fetchAssignments(
      HttpClient httpClient, URI baseUrl, String nodeId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("/nodes/" + nodeId + "/assignments")).GET().build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    List<Object> raw = (List<Object>) Json.parse(response.body());
    List<AssignedInstance> result = new ArrayList<>();
    for (Object entry : raw) {
      Map<String, Object> map = (Map<String, Object>) entry;
      Map<String, Object> moduleIdMap = (Map<String, Object>) map.get("moduleId");
      ModuleId moduleId =
          new ModuleId(
              (String) moduleIdMap.get("name"), Version.parse((String) moduleIdMap.get("version")));
      result.add(
          new AssignedInstance(
              (String) map.get("deploymentName"),
              ((Number) map.get("instanceIndex")).intValue(),
              moduleId,
              (String) map.get("artifactPath")));
    }
    return result;
  }

  // ---- reconciling the locally-supervised set against the control plane's assignments ----

  private static void reconcileAssignments(
      HttpClient httpClient,
      URI baseUrl,
      String nodeId,
      Map<String, SupervisedInstance> supervised,
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker)
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
              javaExecutable,
              commandTail,
              resourceLimiter,
              capacityTracker);
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
      String javaExecutable,
      List<String> commandTail,
      ResourceLimiter resourceLimiter,
      CapacityTracker capacityTracker)
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
    baseCommand.addAll(resourceLimiter.jvmFlags(handle));
    baseCommand.addAll(commandTail);

    RestartTracker restartTracker =
        new RestartTracker(
            Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofMinutes(10));
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
            });

    SupervisedInstance instance = new SupervisedInstance(assigned, supervisor, server, descriptor);
    supervised.put(key, instance);
    capacityTracker.tryAssign(key, descriptor.resourceRequest());
    supervisor.start();

    Thread.ofVirtual()
        .name("gimle-instance-starter-" + key)
        .start(() -> driveInstanceUp(instance, key));
  }

  private static void driveInstanceUp(SupervisedInstance instance, String key) {
    try {
      WorkerConnection connection = instance.server.accept();
      instance.connection = connection;
      Thread.ofVirtual().name("gimle-instance-reader-" + key).start(() -> readLoop(instance, key));

      connection.send(
          new ControlMessage.InstallModule(nextCorrelationId(), instance.assigned.artifactPath()));
      connection.send(
          new ControlMessage.ResolveModule(nextCorrelationId(), instance.assigned.moduleId()));
      connection.send(
          new ControlMessage.StartModule(nextCorrelationId(), instance.assigned.moduleId()));
    } catch (IOException e) {
      log.error("failed to bring up instance {}: {}", key, e.getMessage());
    }
  }

  private static void readLoop(SupervisedInstance instance, String key) {
    try {
      Optional<ControlMessage> received;
      while ((received = instance.connection.receive()).isPresent()) {
        ControlMessage message = received.get();
        if (message instanceof ControlMessage.ModuleStateChanged changed) {
          instance.lifecycleState = changed.state();
        } else if (message instanceof ControlMessage.Nack nack) {
          log.warn("instance {} nacked {}: {}", key, nack.correlationId(), nack.reason());
        }
      }
      log.info("instance {} control channel closed", key);
    } catch (IOException e) {
      log.warn("instance {} control channel failed: {}", key, e.getMessage());
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
