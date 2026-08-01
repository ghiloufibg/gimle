package com.gimle.controlplane.api;

import com.gimle.controlplane.manifest.DeploymentManifestParser;
import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.raft.RaftLog;
import com.gimle.controlplane.raft.RaftNode;
import com.gimle.controlplane.raft.StateMutation;
import com.gimle.controlplane.secret.KeyFileManager;
import com.gimle.controlplane.secret.SecretCipher;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.ObservedHeartbeat;
import com.gimle.controlplane.store.StateStore;
import com.gimle.controlplane.tenant.TenantUsage;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.exception.GimleRaftException;
import com.gimle.core.logging.LogFileReader;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleDescriptor;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's HTTP surface: {@code com.sun.net.httpserver.HttpServer}, JDK-bundled, no
 * framework dependency -- matches the project's explicit non-goal of pulling in
 * Spring/Netty/Quarkus for something this small. Deployment manifests travel as YAML bodies
 * (matching {@code gimle-module.yaml}'s own convention); node registration/heartbeat/assignment
 * traffic travels as hand-rolled JSON (see {@link Json}) -- different audiences, same reasoning
 * {@code ControlMessage}'s text codec used to justify differing from {@code gimle-fabric}'s
 * eventual binary codec.
 */
public final class ApiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

  private final StateStore store;
  private final HttpServer server;
  private final SecretKey secretKey;
  private final RaftNode raftNode;
  private final Map<String, String> peerApiAddresses;
  // HTTP/1.1 explicitly: agents speak plain HttpServer-based HTTP/1.1, never HTTP/2, and pinning
  // avoids HttpClient spending a round trip on an upgrade negotiation that could never succeed.
  private final HttpClient agentHttpClient =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /**
   * Ephemeral in-memory key, never persisted -- fine for tests and any caller that doesn't need
   * secrets to survive a restart, but not real deployments (see the three-argument constructor).
   * Also builds an internal single-node {@link RaftNode} (majority of one, trivially always leader)
   * rather than requiring every existing single-process caller to wire up Raft explicitly.
   */
  public ApiServer(StateStore store, int port) throws IOException {
    this(store, port, ephemeralKeyPath());
  }

  /**
   * {@code secretKeyFilePath} is the control plane's persistent AES-256 secrets master key,
   * generated on first run if absent. Builds an internal single-node {@link RaftNode} exactly like
   * the two-argument constructor -- this overload only changes secrets persistence, not replication
   * topology.
   */
  public ApiServer(StateStore store, int port, Path secretKeyFilePath) throws IOException {
    this(store, port, secretKeyFilePath, singleNodeRaft(store), Map.of());
  }

  /**
   * The real, multi-node-aware constructor: {@code raftNode} is this control-plane node's
   * already-started {@link RaftNode}; {@code peerApiAddresses} maps every peer's Raft address to
   * its HTTP API address, needed only to resolve a not-leader redirect's {@code Location} header to
   * something an HTTP client can actually reach.
   */
  public ApiServer(
      StateStore store,
      int port,
      Path secretKeyFilePath,
      RaftNode raftNode,
      Map<String, String> peerApiAddresses)
      throws IOException {
    this(store, port, KeyFileManager.loadOrCreate(secretKeyFilePath), raftNode, peerApiAddresses);
  }

  private ApiServer(
      StateStore store,
      int port,
      SecretKey secretKey,
      RaftNode raftNode,
      Map<String, String> peerApiAddresses)
      throws IOException {
    this.store = store;
    this.secretKey = secretKey;
    this.raftNode = raftNode;
    this.peerApiAddresses = Map.copyOf(peerApiAddresses);
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/deployments/", this::handleDeployment);
    server.createContext("/deployments", this::handleDeploymentsList);
    server.createContext("/nodes/", this::handleNode);
    server.createContext("/nodes", this::handleNodesList);
    server.createContext("/tenants/", this::handleTenant);
    server.createContext("/tenants", this::handleTenantsList);
    server.createContext("/config/", this::handleConfig);
    server.createContext("/logs/", this::handleLogs);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Registers a static-file context at {@code /console} serving the built SPA under {@code
   * staticRoot}, with client-side-route fallback to whichever shell file the SPA's tooling produced
   * -- {@code _shell.html} if present (TanStack Start's SPA mode), else the conventional {@code
   * index.html}. Opt-in: no constructor calls this, so every existing caller/test is unaffected
   * until something explicitly wires a console directory in.
   */
  public void serveConsole(Path staticRoot) throws IOException {
    String shellFileName =
        Files.isRegularFile(staticRoot.resolve("_shell.html")) ? "_shell.html" : "index.html";
    server.createContext("/console", new ConsoleStaticHandler(staticRoot, shellFileName));
  }

  /**
   * A single-node Raft cluster (peer set = {@code {self}}, majority = 1, so this node is trivially
   * always leader) backed by a fresh temp directory -- what every constructor that predates Raft
   * gets automatically, so existing single-process callers/tests need no changes.
   */
  private static RaftNode singleNodeRaft(StateStore store) throws IOException {
    Path dir = Files.createTempDirectory("gimle-apiserver-ephemeral-raft-");
    RaftNode node = new RaftNode("self", Map.of(), new RaftLog(dir.resolve("raft")), store);
    node.start();
    return node;
  }

  /** A fresh temp path per JVM run -- the ephemeral constructor never intends key reuse anyway. */
  private static Path ephemeralKeyPath() throws IOException {
    Path dir = Files.createTempDirectory("gimle-apiserver-ephemeral-key-");
    return dir.resolve("secret.key");
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---- /deployments/{name} ----

  private void handleDeployment(HttpExchange exchange) {
    try {
      String name = pathSegmentAfter(exchange, "/deployments/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing deployment name");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handlePutDeployment(exchange, name);
        case "GET" -> handleGetDeployment(exchange, name);
        case "DELETE" -> handleDeleteDeployment(exchange, name);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondNotLeader(exchange);
    } catch (GimleManifestException | IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("deployment request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutDeployment(HttpExchange exchange, String name) throws IOException {
    DeploymentSpec spec = DeploymentManifestParser.parse(exchange.getRequestBody());
    if (!spec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + spec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    Optional<String> quotaRejection = checkTenantQuota(spec);
    if (quotaRejection.isPresent()) {
      respond(exchange, 409, quotaRejection.get());
      return;
    }
    raftNode.propose(new StateMutation.PutDeployment(spec));
    respond(exchange, 200, "ok");
  }

  /**
   * Admission-time quota check: absent if the deployment is untenanted (no check to run) or would
   * keep the tenant within quota; present with a rejection reason otherwise. Reads the module
   * descriptor control-plane-side, the same way {@code DeploymentReconciler} already does to learn
   * a resource request before any node has resolved anything -- an unreadable artifact rejects the
   * submission outright here (unlike {@code DeploymentReconciler}, which just retries next tick
   * with nothing yet at stake), since admission can't safely let through a submission it has no way
   * to verify against the tenant's quota.
   */
  private Optional<String> checkTenantQuota(DeploymentSpec spec) {
    if (spec.tenantId().isEmpty()) {
      return Optional.empty();
    }
    String tenantId = spec.tenantId().get();
    Optional<Tenant> tenant = store.getTenant(tenantId);
    if (tenant.isEmpty()) {
      return Optional.of("unknown tenantId: " + tenantId);
    }
    ModuleDescriptor descriptor;
    try {
      descriptor = ModuleArtifactReader.read(Path.of(spec.artifactPath())).descriptor();
    } catch (RuntimeException e) {
      return Optional.of(
          "cannot verify tenant quota: artifact unreadable at " + spec.artifactPath());
    }
    TenantUsage.Usage existing = TenantUsage.currentlyAssigned(store, tenantId, spec.name());
    TenantUsage.Usage withThisSubmission =
        existing.plus(
            descriptor.resourceRequest().memoryBytes() * spec.replicas(),
            descriptor.resourceRequest().cpuMillicores() * spec.replicas(),
            spec.replicas());
    if (withThisSubmission.exceeds(tenant.get().quota())) {
      return Optional.of(
          "deployment "
              + spec.name()
              + " would push tenant "
              + tenantId
              + " past its resource quota");
    }
    return Optional.empty();
  }

  private void handleGetDeployment(HttpExchange exchange, String name) throws IOException {
    Optional<DeploymentSpec> spec = store.getDeployment(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such deployment: " + name);
      return;
    }
    respondJson(exchange, 200, deploymentStatus(spec.get()));
  }

  private void handleDeleteDeployment(HttpExchange exchange, String name) throws IOException {
    raftNode.propose(new StateMutation.RemoveDeployment(name));
    respond(exchange, 200, "ok");
  }

  /** Every deployment, in the same shape {@link #handleGetDeployment} returns for one. */
  private void handleDeploymentsList(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange, 200, store.listDeployments().stream().map(this::deploymentStatus).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("deployments list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private Map<String, Object> deploymentStatus(DeploymentSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", moduleIdToJson(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    specMap.put("replicas", spec.replicas());
    spec.tenantId().ifPresent(tenantId -> specMap.put("tenantId", tenantId));

    List<Map<String, Object>> instances = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignmentsFor(spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      findObservation(assignment)
          .ifPresent(obs -> instance.put("observation", observationToJson(obs)));
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    status.put("unplacedCount", spec.replicas() - instances.size());
    status.put("quotaViolating", store.isQuotaViolating(spec.name()));
    return status;
  }

  private Optional<InstanceObservation> findObservation(InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(
            heartbeat ->
                heartbeat.instances().stream()
                    .filter(
                        obs ->
                            obs.deploymentName().equals(assignment.deploymentName())
                                && obs.instanceIndex() == assignment.instanceIndex())
                    .findFirst());
  }

  // ---- /nodes/{nodeId}/... ----

  private void handleNode(HttpExchange exchange) {
    try {
      String path = exchange.getRequestURI().getPath();
      String tail = path.substring("/nodes/".length());
      int slash = tail.indexOf('/');
      if (slash < 0) {
        respond(exchange, 400, "expected /nodes/{nodeId}/register|heartbeat|assignments");
        return;
      }
      String nodeId = tail.substring(0, slash);
      String action = tail.substring(slash + 1);
      if (nodeId.isBlank()) {
        respond(exchange, 400, "missing nodeId");
        return;
      }
      switch (action) {
        case "register" -> handleRegister(exchange, nodeId);
        case "heartbeat" -> handleHeartbeat(exchange, nodeId);
        case "assignments" -> handleAssignments(exchange, nodeId);
        default -> respond(exchange, 404, "unknown node endpoint: " + action);
      }
    } catch (GimleRaftException e) {
      respondNotLeader(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("node request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleRegister(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    NodeCapabilities capabilities = capabilitiesFromJson((Map<?, ?>) body.get("capabilities"));
    Object apiAddress = body.get("apiAddress");
    raftNode.propose(
        new StateMutation.PutNodeRegistration(
            new NodeRegistration(
                nodeId,
                capabilities,
                apiAddress == null ? Optional.empty() : Optional.of((String) apiAddress))));
    respond(exchange, 200, "ok");
  }

  /**
   * Heartbeats are deliberately never Raft-replicated: high-frequency, tolerate a brief gap after a
   * leader change, and replicating every one would make the log's write rate scale with cluster
   * size for no correctness benefit. Only the leader's own {@code StateStore} ever receives them
   * directly -- a non-leader rejects with the same not-leader response every other write uses, even
   * though this path never touches the Raft log.
   */
  private void handleHeartbeat(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    if (!raftNode.isLeader()) {
      respondNotLeader(exchange);
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    ResourceUsageSnapshot capacity = capacityFromJson((Map<?, ?>) body.get("capacity"));
    List<InstanceObservation> instances = new ArrayList<>();
    for (Object entry : (List<?>) body.get("instances")) {
      instances.add(observationFromJson((Map<?, ?>) entry));
    }
    store.putNodeHeartbeat(new NodeHeartbeat(nodeId, capacity, instances));
    respond(exchange, 200, "ok");
  }

  private void handleAssignments(HttpExchange exchange, String nodeId) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<Map<String, Object>> assigned = new ArrayList<>();
    for (InstanceAssignment assignment : store.listAssignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<DeploymentSpec> spec = store.getDeployment(assignment.deploymentName());
      if (spec.isEmpty()) {
        continue; // stale assignment; DeploymentReconciler will remove it shortly
      }
      // moduleId/artifactPath come from the assignment itself, not the deployment's current spec:
      // mid-rolling-update, an index that hasn't migrated yet must keep telling its agent to run
      // whatever it was actually placed with, not the spec's already-advanced target version. An
      // assignment that never specified its own (the three-argument constructor, predating rolling
      // updates) falls back to the spec's, matching the only behavior that existed before.
      ModuleId moduleId =
          assignment.moduleId().equals(InstanceAssignment.UNSPECIFIED_MODULE)
              ? spec.get().moduleId()
              : assignment.moduleId();
      String artifactPath =
          assignment.artifactPath().isBlank()
              ? spec.get().artifactPath()
              : assignment.artifactPath();
      AssignedInstance instance =
          new AssignedInstance(
              assignment.deploymentName(),
              assignment.instanceIndex(),
              moduleId,
              artifactPath,
              spec.get().tenantId());
      assigned.add(assignedInstanceToJson(instance));
    }
    respondJson(exchange, 200, assigned);
  }

  /** Every registered node, with its capabilities and last-heartbeat time if it's ever sent one. */
  private void handleNodesList(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      List<Map<String, Object>> nodes = new ArrayList<>();
      for (NodeRegistration registration : store.listNodeRegistrations()) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", registration.nodeId());
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put(
            "supportedTiers",
            registration.capabilities().supportedTiers().stream().map(Enum::name).toList());
        node.put("capabilities", capabilities);
        store
            .getNodeHeartbeat(registration.nodeId())
            .ifPresent(
                observed -> {
                  node.put("lastHeartbeatAt", observed.receivedAt().toString());
                  node.put("capacity", capacityToJson(observed.heartbeat().capacity()));
                });
        nodes.add(node);
      }
      respondJson(exchange, 200, nodes);
    } catch (IOException | RuntimeException e) {
      log.warn("nodes list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  // ---- (de)serialization ----

  private static Map<String, Object> moduleIdToJson(ModuleId id) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", id.name());
    map.put("version", id.version().toString());
    return map;
  }

  private static ModuleId moduleIdFromJson(Map<?, ?> map) {
    return new ModuleId((String) map.get("name"), Version.parse((String) map.get("version")));
  }

  private static NodeCapabilities capabilitiesFromJson(Map<?, ?> map) {
    List<?> tiers = (List<?>) map.get("supportedTiers");
    Set<IsolationTier> supportedTiers = new LinkedHashSet<>();
    for (Object tier : tiers) {
      supportedTiers.add(IsolationTier.valueOf((String) tier));
    }
    return new NodeCapabilities(supportedTiers);
  }

  private static Map<String, Object> capacityToJson(ResourceUsageSnapshot capacity) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("totalMemoryBytes", capacity.totalMemoryBytes());
    map.put("assignedMemoryBytes", capacity.assignedMemoryBytes());
    map.put("totalCpuMillicores", capacity.totalCpuMillicores());
    map.put("assignedCpuMillicores", capacity.assignedCpuMillicores());
    return map;
  }

  private static ResourceUsageSnapshot capacityFromJson(Map<?, ?> map) {
    return new ResourceUsageSnapshot(
        ((Number) map.get("totalMemoryBytes")).longValue(),
        ((Number) map.get("assignedMemoryBytes")).longValue(),
        ((Number) map.get("totalCpuMillicores")).longValue(),
        ((Number) map.get("assignedCpuMillicores")).longValue());
  }

  private static InstanceObservation observationFromJson(Map<?, ?> map) {
    return new InstanceObservation(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        moduleIdFromJson((Map<?, ?>) map.get("moduleId")),
        (String) map.get("lifecycleState"),
        (Boolean) map.get("alive"),
        (Boolean) map.get("ready"),
        numberField(map, "requestRatePerSecond", 0.0).doubleValue(),
        numberField(map, "queueDepth", 0).intValue(),
        numberField(map, "cpuMillicoresUsed", 0L).longValue(),
        numberField(map, "memoryBytesUsed", 0L).longValue());
  }

  private static Number numberField(Map<?, ?> map, String key, Number defaultValue) {
    Object value = map.get(key);
    return value instanceof Number number ? number : defaultValue;
  }

  private static Map<String, Object> observationToJson(InstanceObservation obs) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("moduleId", moduleIdToJson(obs.moduleId()));
    map.put("lifecycleState", obs.lifecycleState());
    map.put("alive", obs.alive());
    map.put("ready", obs.ready());
    map.put("requestRatePerSecond", obs.requestRatePerSecond());
    map.put("queueDepth", obs.queueDepth());
    map.put("cpuMillicoresUsed", obs.cpuMillicoresUsed());
    map.put("memoryBytesUsed", obs.memoryBytesUsed());
    return map;
  }

  private static Map<String, Object> assignedInstanceToJson(AssignedInstance instance) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("deploymentName", instance.deploymentName());
    map.put("instanceIndex", instance.instanceIndex());
    map.put("moduleId", moduleIdToJson(instance.moduleId()));
    map.put("artifactPath", instance.artifactPath());
    instance.tenantId().ifPresent(tenantId -> map.put("tenantId", tenantId));
    return map;
  }

  // ---- /tenants and /tenants/{id} ----

  private void handleTenantsList(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      respondJson(
          exchange, 200, store.listTenants().stream().map(ApiServer::tenantToJson).toList());
    } catch (IOException | RuntimeException e) {
      log.warn("tenants list request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handleTenant(HttpExchange exchange) {
    try {
      String id = pathSegmentAfter(exchange, "/tenants/");
      if (id.isBlank()) {
        respond(exchange, 400, "missing tenant id");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handlePutTenant(exchange, id);
        case "GET" -> handleGetTenant(exchange, id);
        case "DELETE" -> handleDeleteTenant(exchange, id);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondNotLeader(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("tenant request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutTenant(HttpExchange exchange, String id) throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    Map<?, ?> quotaMap = (Map<?, ?>) body.get("quota");
    ResourceQuota quota =
        new ResourceQuota(
            ((Number) quotaMap.get("maxMemoryBytes")).longValue(),
            ((Number) quotaMap.get("maxCpuMillicores")).longValue(),
            ((Number) quotaMap.get("maxInstances")).intValue());
    raftNode.propose(new StateMutation.PutTenant(new Tenant(id, quota)));
    respond(exchange, 200, "ok");
  }

  private void handleGetTenant(HttpExchange exchange, String id) throws IOException {
    Optional<Tenant> tenant = store.getTenant(id);
    if (tenant.isEmpty()) {
      respond(exchange, 404, "no such tenant: " + id);
      return;
    }
    respondJson(exchange, 200, tenantToJson(tenant.get()));
  }

  private void handleDeleteTenant(HttpExchange exchange, String id) throws IOException {
    raftNode.propose(new StateMutation.RemoveTenant(id));
    respond(exchange, 200, "ok");
  }

  private static Map<String, Object> tenantToJson(Tenant tenant) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", tenant.id());
    Map<String, Object> quota = new LinkedHashMap<>();
    quota.put("maxMemoryBytes", tenant.quota().maxMemoryBytes());
    quota.put("maxCpuMillicores", tenant.quota().maxCpuMillicores());
    quota.put("maxInstances", tenant.quota().maxInstances());
    map.put("quota", quota);
    return map;
  }

  // ---- /config/{tenantId} and /config/{tenantId}/{key} ----

  private void handleConfig(HttpExchange exchange) {
    try {
      String tail = pathSegmentAfter(exchange, "/config/");
      if (tail.isBlank()) {
        respond(exchange, 400, "expected /config/{tenantId} or /config/{tenantId}/{key}");
        return;
      }
      int slash = tail.indexOf('/');
      String tenantId = slash < 0 ? tail : tail.substring(0, slash);
      if (tenantId.isBlank()) {
        respond(exchange, 400, "missing tenantId");
        return;
      }
      if (slash < 0) {
        if (!"GET".equals(exchange.getRequestMethod())) {
          respond(exchange, 405, "method not allowed");
          return;
        }
        handleListConfig(exchange, tenantId);
        return;
      }
      String key = tail.substring(slash + 1);
      if (key.isBlank()) {
        respond(exchange, 400, "missing config key");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handlePutConfig(exchange, tenantId, key);
        case "DELETE" -> handleDeleteConfig(exchange, tenantId, key);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleRaftException e) {
      respondNotLeader(exchange);
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("config request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handlePutConfig(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    Map<?, ?> body = (Map<?, ?>) Json.parse(readBody(exchange));
    String value = (String) body.get("value");
    boolean encrypted = Boolean.TRUE.equals(body.get("encrypted"));
    byte[] stored =
        encrypted
            ? SecretCipher.encrypt(value.getBytes(StandardCharsets.UTF_8), secretKey)
            : value.getBytes(StandardCharsets.UTF_8);
    raftNode.propose(
        new StateMutation.PutConfigEntry(new ConfigEntry(tenantId, key, stored, encrypted)));
    respond(exchange, 200, "ok");
  }

  private void handleDeleteConfig(HttpExchange exchange, String tenantId, String key)
      throws IOException {
    raftNode.propose(new StateMutation.RemoveConfigEntry(tenantId, key));
    respond(exchange, 200, "ok");
  }

  /**
   * Returns every entry for {@code tenantId}, decrypted -- the node agent's fetch point: an agent
   * fetches a deployment's tenant-scoped {@code ConfigEntry} set from the control plane, already
   * decrypted server-side. Plaintext leaves this process only over the same authenticated
   * control-plane connection every other agent request already uses.
   */
  private void handleListConfig(HttpExchange exchange, String tenantId) throws IOException {
    List<Map<String, Object>> list = new ArrayList<>();
    for (ConfigEntry entry : store.listConfigEntriesFor(tenantId)) {
      byte[] plaintext =
          entry.encrypted() ? SecretCipher.decrypt(entry.value(), secretKey) : entry.value();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("key", entry.key());
      m.put("value", new String(plaintext, StandardCharsets.UTF_8));
      m.put("encrypted", entry.encrypted());
      list.add(m);
    }
    respondJson(exchange, 200, list);
  }

  // ---- /logs/controlplane, /logs/nodes/{nodeId}, /logs/instances/{name}/{idx} ----

  /**
   * Log reads are GETs against whichever control-plane replica receives them, which then makes its
   * own direct call to the target agent -- no write/consensus involved, so §5's leader-redirect
   * handling doesn't apply here (matches {@code log-explorer-design.md} §6).
   */
  private void handleLogs(HttpExchange exchange) {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        respond(exchange, 405, "method not allowed");
        return;
      }
      String tail = pathSegmentAfter(exchange, "/logs/");
      if (tail.equals("controlplane")) {
        handleControlPlaneLogs(exchange);
      } else if (tail.startsWith("nodes/")) {
        handleNodeLogsProxy(exchange, tail.substring("nodes/".length()));
      } else if (tail.startsWith("instances/")) {
        handleInstanceLogsProxy(exchange, tail.substring("instances/".length()));
      } else {
        respond(exchange, 404, "unknown logs endpoint: " + tail);
      }
    } catch (IllegalArgumentException e) {
      respondQuietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("logs request failed: {}", e.getMessage());
      respondQuietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  /** Served directly from this process's own platform log -- it's the process answering. */
  private void handleControlPlaneLogs(HttpExchange exchange) throws IOException {
    Map<String, String> query = parseQuery(exchange);
    String category = query.getOrDefault("category", "PLATFORM");
    if (!"PLATFORM".equals(category)) {
      respond(exchange, 400, "controlplane logs only support category=PLATFORM");
      return;
    }
    Path file =
        Path.of(System.getProperty("gimle.log.root", "gimle-logs"))
            .resolve("controlplane-platform.log");
    respondLogFile(exchange, file, query);
  }

  private void handleNodeLogsProxy(HttpExchange exchange, String nodeId) throws IOException {
    if (nodeId.isBlank()) {
      respond(exchange, 400, "missing nodeId");
      return;
    }
    proxyToAgent(exchange, nodeId, "/logs/nodes/" + nodeId);
  }

  private void handleInstanceLogsProxy(HttpExchange exchange, String tail) throws IOException {
    // limit=3: deploymentName, instanceIndex, and an optional sub-path (e.g. AgentLogServer's
    // "crashdumps" or "crashdumps/<name>") -- a plain 2-way split on the first slash used to
    // swallow anything past the instanceIndex into a failed Integer.parseInt, breaking any
    // sub-path entirely.
    String[] parts = tail.split("/", 3);
    if (parts.length < 2) {
      respond(exchange, 400, "expected /logs/instances/{deploymentName}/{instanceIndex}[/...]");
      return;
    }
    String deploymentName = parts[0];
    int instanceIndex;
    try {
      instanceIndex = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      respond(exchange, 400, "invalid instanceIndex");
      return;
    }
    String nodeId =
        store.listAssignmentsFor(deploymentName).stream()
            .filter(a -> a.instanceIndex() == instanceIndex)
            .map(InstanceAssignment::nodeId)
            .findFirst()
            .orElse(null);
    if (nodeId == null) {
      respond(exchange, 404, "no placement found for " + deploymentName + "#" + instanceIndex);
      return;
    }
    // Forward the original tail verbatim (not reconstructed from just name/index) so any sub-path
    // -- crashdumps, crashdumps/<name> -- survives the proxy hop unchanged.
    proxyToAgent(exchange, nodeId, "/logs/instances/" + tail);
  }

  /** Looks up the owning node's self-reported log-server address and forwards the request as-is. */
  private void proxyToAgent(HttpExchange exchange, String nodeId, String path) throws IOException {
    Optional<NodeRegistration> registration = store.getNodeRegistration(nodeId);
    Optional<String> apiAddress = registration.flatMap(NodeRegistration::apiAddress);
    if (apiAddress.isEmpty()) {
      respond(exchange, 502, "node " + nodeId + " has no known log-server address");
      return;
    }
    String query = exchange.getRequestURI().getRawQuery();
    URI target =
        URI.create("http://" + apiAddress.get() + path + (query != null ? "?" + query : ""));
    HttpRequest request = HttpRequest.newBuilder(target).GET().build();

    if (query != null && query.contains("follow=true")) {
      proxyFollowToAgent(exchange, apiAddress.get(), request);
      return;
    }

    HttpResponse<InputStream> response;
    try {
      response = agentHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respond(exchange, 502, "interrupted while proxying to agent " + apiAddress.get());
      return;
    } catch (IOException e) {
      respond(
          exchange, 502, "failed to reach agent at " + apiAddress.get() + ": " + e.getMessage());
      return;
    }
    String contentType =
        response.headers().firstValue("Content-Type").orElse("application/octet-stream");
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(response.statusCode(), 0);
    try (InputStream body = response.body();
        OutputStream out = exchange.getResponseBody()) {
      body.transferTo(out);
    }
  }

  /**
   * {@code follow=true} means an indefinite chunked response with no end in sight, and {@code
   * HttpClient.send()} + {@code BodyHandlers.ofInputStream()} -- which works fine for the bounded,
   * non-follow proxy path above -- never delivers a single byte for this shape of response
   * (confirmed empirically: a direct request straight to the agent's own log server streams
   * immediately, the identical request through this proxy using {@code send()}/{@code
   * ofInputStream()} sat silent for the whole test window). {@code ofByteArrayConsumer} sidesteps
   * that: it's driven by the reactive {@code Flow} subscription underneath, invoked as chunks
   * genuinely arrive rather than via a blocking synchronous read, so bytes reach the exchange's
   * output stream as they're produced instead of never at all.
   */
  private void proxyFollowToAgent(HttpExchange exchange, String apiAddress, HttpRequest request)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      agentHttpClient
          .sendAsync(
              request,
              HttpResponse.BodyHandlers.ofByteArrayConsumer(
                  chunk -> {
                    if (chunk.isPresent()) {
                      try {
                        out.write(chunk.get());
                        out.flush();
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    }
                  }))
          .join();
    } catch (CompletionException | UncheckedIOException e) {
      log.debug(
          "follow proxy session to agent {} ended: {}", apiAddress, String.valueOf(e.getMessage()));
    }
  }

  private static void respondLogFile(HttpExchange exchange, Path file, Map<String, String> query)
      throws IOException {
    boolean follow = "true".equals(query.get("follow"));
    String cursor = query.get("cursor");
    int limit = parseLimit(query.get("limit"));
    int maxFiles = LogFileReader.configuredMaxFiles();
    if (follow) {
      exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream out = exchange.getResponseBody()) {
        LogFileReader.streamFollow(file, maxFiles, cursor, Duration.ofMillis(500), out);
      } catch (IOException e) {
        log.debug("controlplane log follow session ended: {}", e.getMessage());
      }
      return;
    }
    // "since" (readAfter, forward polling: "what's new since my last poll") is a genuinely
    // different operation from "cursor" (readOlder, backward paging: "Load older") -- see
    // AgentLogServer.readPage's javadoc for the real duplication bug this distinction fixes.
    String since = query.get("since");
    LogFileReader.LogPage page;
    if (since != null) {
      List<Map<String, Object>> lines = LogFileReader.readAfter(file, maxFiles, since);
      String newerCursor =
          lines.isEmpty() ? since : String.valueOf(lines.get(lines.size() - 1).get("timestamp"));
      page = new LogFileReader.LogPage(lines, null, newerCursor);
    } else {
      page = LogFileReader.readOlder(file, maxFiles, cursor, limit);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("lines", page.lines());
    body.put("olderCursor", page.olderCursor());
    body.put("newerCursor", page.newerCursor());
    respondJson(exchange, 200, body);
  }

  private static int parseLimit(String raw) {
    if (raw == null) {
      return 200;
    }
    try {
      return Math.max(1, Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      return 200;
    }
  }

  private static Map<String, String> parseQuery(HttpExchange exchange) {
    Map<String, String> result = new LinkedHashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isBlank()) {
      return result;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }

  // ---- HTTP plumbing ----

  private static String pathSegmentAfter(HttpExchange exchange, String prefix) {
    String path = exchange.getRequestURI().getPath();
    return path.substring(prefix.length());
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondQuietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }

  /**
   * A write rejected by a non-leader: {@code 307} preserves the original method on redirect
   * (required for PUT/POST/DELETE), with a {@code Location} header pointing at the current leader's
   * HTTP address when known, plus a JSON body serving a Gimlé-aware caller that reads structured
   * fields instead of following the redirect.
   */
  private void respondNotLeader(HttpExchange exchange) {
    try {
      Optional<String> leaderRaftId = raftNode.leaderHint();
      Optional<String> leaderApiAddress = leaderRaftId.map(peerApiAddresses::get);
      leaderApiAddress.ifPresent(
          address ->
              exchange
                  .getResponseHeaders()
                  .add("Location", "http://" + address + exchange.getRequestURI().getPath()));
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("error", "not-leader");
      body.put("leaderRaftId", leaderRaftId.orElse(null));
      body.put("leaderApiAddress", leaderApiAddress.orElse(null));
      respondJson(exchange, 307, body);
    } catch (IOException e) {
      log.warn("failed to write not-leader response: {}", e.getMessage());
    }
  }
}
