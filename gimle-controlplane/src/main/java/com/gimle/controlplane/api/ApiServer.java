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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control plane's HTTP surface (design §4): {@code com.sun.net.httpserver.HttpServer}, JDK-
 * bundled, no framework dependency -- matches the project's explicit non-goal of pulling in
 * Spring/Netty/Quarkus for something this small. Deployment manifests travel as YAML bodies
 * (matching {@code gimle-module.yaml}'s own convention); node registration/heartbeat/assignment
 * traffic travels as hand-rolled JSON (see {@link Json}) -- different audiences, same reasoning
 * {@code ControlMessage}'s text codec used to justify differing from {@code gimle-fabric}'s
 * eventual binary codec (design §11.1).
 */
public final class ApiServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

  private final StateStore store;
  private final HttpServer server;
  private final SecretKey secretKey;
  private final RaftNode raftNode;
  private final Map<String, String> peerApiAddresses;

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
   * {@code secretKeyFilePath} (Phase 5 design §6.1) is the control plane's persistent AES-256
   * secrets master key, generated on first run if absent. Builds an internal single-node {@link
   * RaftNode} exactly like the two-argument constructor -- this overload only changes secrets
   * persistence, not replication topology.
   */
  public ApiServer(StateStore store, int port, Path secretKeyFilePath) throws IOException {
    this(store, port, secretKeyFilePath, singleNodeRaft(store), Map.of());
  }

  /**
   * The real, multi-node-aware constructor (raft design §2.6/§3): {@code raftNode} is this
   * control-plane node's already-started {@link RaftNode}; {@code peerApiAddresses} maps every
   * peer's Raft address to its HTTP API address, needed only to resolve a not-leader redirect's
   * {@code Location} header to something an HTTP client can actually reach.
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
    server.createContext("/nodes/", this::handleNode);
    server.createContext("/tenants/", this::handleTenant);
    server.createContext("/tenants", this::handleTenantsList);
    server.createContext("/config/", this::handleConfig);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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
   * Admission-time quota check (Phase 5 design §5.2): absent if the deployment is untenanted (no
   * check to run) or would keep the tenant within quota; present with a rejection reason otherwise.
   * Reads the module descriptor control-plane-side, the same way {@code DeploymentReconciler}
   * already does to learn a resource request before any node has resolved anything -- an unreadable
   * artifact rejects the submission outright here (unlike {@code DeploymentReconciler}, which just
   * retries next tick with nothing yet at stake), since admission can't safely let through a
   * submission it has no way to verify against the tenant's quota.
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
    raftNode.propose(
        new StateMutation.PutNodeRegistration(new NodeRegistration(nodeId, capabilities)));
    respond(exchange, 200, "ok");
  }

  /**
   * Heartbeats are deliberately never Raft-replicated (raft design §2.1): high-frequency, tolerate
   * a brief gap after a leader change, and replicating every one would make the log's write rate
   * scale with cluster size for no correctness benefit. Only the leader's own {@code StateStore}
   * ever receives them directly -- a non-leader rejects with the same not-leader response every
   * other write uses, even though this path never touches the Raft log.
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
      // whatever it was actually placed with, not the spec's already-advanced target version
      // (Phase 4 §9). An assignment that never specified its own (the pre-Phase-4 three-argument
      // constructor) falls back to the spec's, matching the only behavior that existed before.
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

  // ---- /tenants and /tenants/{id} (Phase 5 design §5.1) ----

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

  // ---- /config/{tenantId} and /config/{tenantId}/{key} (Phase 5 design §6) ----

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
   * Returns every entry for {@code tenantId}, decrypted -- the node agent's fetch point (Phase 5
   * design §6.3): "the node agent... fetches that deployment's tenant-scoped ConfigEntry set from
   * the control plane (decrypted server-side...)". Plaintext leaves this process only over the same
   * authenticated control-plane connection every other agent request already uses.
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
   * A write rejected by a non-leader (raft design §2.6): {@code 307} preserves the original method
   * on redirect (required for PUT/POST/DELETE), with a {@code Location} header pointing at the
   * current leader's HTTP address when known, plus a JSON body serving a Gimlé-aware caller that
   * reads structured fields instead of following the redirect.
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
