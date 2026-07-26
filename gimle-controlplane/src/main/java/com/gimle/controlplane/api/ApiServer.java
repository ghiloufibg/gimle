package com.gimle.controlplane.api;

import com.gimle.controlplane.manifest.DeploymentManifestParser;
import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.ObservedHeartbeat;
import com.gimle.controlplane.store.StateStore;
import com.gimle.core.exception.GimleManifestException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AssignedInstance;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.Json;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
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

  public ApiServer(StateStore store, int port) throws IOException {
    this.store = store;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/deployments/", this::handle_deployment);
    server.createContext("/nodes/", this::handle_node);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
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

  private void handle_deployment(HttpExchange exchange) {
    try {
      String name = path_segment_after(exchange, "/deployments/");
      if (name.isBlank()) {
        respond(exchange, 400, "missing deployment name");
        return;
      }
      switch (exchange.getRequestMethod()) {
        case "PUT" -> handle_put_deployment(exchange, name);
        case "GET" -> handle_get_deployment(exchange, name);
        case "DELETE" -> handle_delete_deployment(exchange, name);
        default -> respond(exchange, 405, "method not allowed");
      }
    } catch (GimleManifestException | IllegalArgumentException e) {
      respond_quietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("deployment request failed: {}", e.getMessage());
      respond_quietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handle_put_deployment(HttpExchange exchange, String name) throws IOException {
    DeploymentSpec spec = DeploymentManifestParser.parse(exchange.getRequestBody());
    if (!spec.name().equals(name)) {
      respond(
          exchange,
          400,
          "manifest name '" + spec.name() + "' does not match URL path '" + name + "'");
      return;
    }
    store.put_deployment(spec);
    respond(exchange, 200, "ok");
  }

  private void handle_get_deployment(HttpExchange exchange, String name) throws IOException {
    Optional<DeploymentSpec> spec = store.get_deployment(name);
    if (spec.isEmpty()) {
      respond(exchange, 404, "no such deployment: " + name);
      return;
    }
    respond_json(exchange, 200, deployment_status(spec.get()));
  }

  private void handle_delete_deployment(HttpExchange exchange, String name) throws IOException {
    store.remove_deployment(name);
    respond(exchange, 200, "ok");
  }

  private Map<String, Object> deployment_status(DeploymentSpec spec) {
    Map<String, Object> specMap = new LinkedHashMap<>();
    specMap.put("name", spec.name());
    specMap.put("moduleId", module_id_to_json(spec.moduleId()));
    specMap.put("artifactPath", spec.artifactPath());
    specMap.put("replicas", spec.replicas());

    List<Map<String, Object>> instances = new ArrayList<>();
    for (InstanceAssignment assignment : store.list_assignments_for(spec.name())) {
      Map<String, Object> instance = new LinkedHashMap<>();
      instance.put("instanceIndex", assignment.instanceIndex());
      instance.put("nodeId", assignment.nodeId());
      find_observation(assignment)
          .ifPresent(obs -> instance.put("observation", observation_to_json(obs)));
      instances.add(instance);
    }

    Map<String, Object> status = new LinkedHashMap<>();
    status.put("spec", specMap);
    status.put("instances", instances);
    status.put("unplacedCount", spec.replicas() - instances.size());
    return status;
  }

  private Optional<InstanceObservation> find_observation(InstanceAssignment assignment) {
    return store
        .get_node_heartbeat(assignment.nodeId())
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

  private void handle_node(HttpExchange exchange) {
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
        case "register" -> handle_register(exchange, nodeId);
        case "heartbeat" -> handle_heartbeat(exchange, nodeId);
        case "assignments" -> handle_assignments(exchange, nodeId);
        default -> respond(exchange, 404, "unknown node endpoint: " + action);
      }
    } catch (IllegalArgumentException e) {
      respond_quietly(exchange, 400, String.valueOf(e.getMessage()));
    } catch (IOException | RuntimeException e) {
      log.warn("node request failed: {}", e.getMessage());
      respond_quietly(exchange, 500, "internal error");
    } finally {
      exchange.close();
    }
  }

  private void handle_register(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(read_body(exchange));
    NodeCapabilities capabilities = capabilities_from_json((Map<?, ?>) body.get("capabilities"));
    store.put_node_registration(new NodeRegistration(nodeId, capabilities));
    respond(exchange, 200, "ok");
  }

  private void handle_heartbeat(HttpExchange exchange, String nodeId) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<?, ?> body = (Map<?, ?>) Json.parse(read_body(exchange));
    ResourceUsageSnapshot capacity = capacity_from_json((Map<?, ?>) body.get("capacity"));
    List<InstanceObservation> instances = new ArrayList<>();
    for (Object entry : (List<?>) body.get("instances")) {
      instances.add(observation_from_json((Map<?, ?>) entry));
    }
    store.put_node_heartbeat(new NodeHeartbeat(nodeId, capacity, instances));
    respond(exchange, 200, "ok");
  }

  private void handle_assignments(HttpExchange exchange, String nodeId) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<Map<String, Object>> assigned = new ArrayList<>();
    for (InstanceAssignment assignment : store.list_assignments()) {
      if (!assignment.nodeId().equals(nodeId)) {
        continue;
      }
      Optional<DeploymentSpec> spec = store.get_deployment(assignment.deploymentName());
      if (spec.isEmpty()) {
        continue; // stale assignment; DeploymentReconciler will remove it shortly
      }
      AssignedInstance instance =
          new AssignedInstance(
              assignment.deploymentName(),
              assignment.instanceIndex(),
              spec.get().moduleId(),
              spec.get().artifactPath());
      assigned.add(assigned_instance_to_json(instance));
    }
    respond_json(exchange, 200, assigned);
  }

  // ---- (de)serialization ----

  private static Map<String, Object> module_id_to_json(ModuleId id) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", id.name());
    map.put("version", id.version().toString());
    return map;
  }

  private static ModuleId module_id_from_json(Map<?, ?> map) {
    return new ModuleId((String) map.get("name"), Version.parse((String) map.get("version")));
  }

  private static NodeCapabilities capabilities_from_json(Map<?, ?> map) {
    List<?> tiers = (List<?>) map.get("supportedTiers");
    Set<IsolationTier> supportedTiers = new LinkedHashSet<>();
    for (Object tier : tiers) {
      supportedTiers.add(IsolationTier.valueOf((String) tier));
    }
    return new NodeCapabilities(supportedTiers);
  }

  private static ResourceUsageSnapshot capacity_from_json(Map<?, ?> map) {
    return new ResourceUsageSnapshot(
        ((Number) map.get("totalMemoryBytes")).longValue(),
        ((Number) map.get("assignedMemoryBytes")).longValue(),
        ((Number) map.get("totalCpuMillicores")).longValue(),
        ((Number) map.get("assignedCpuMillicores")).longValue());
  }

  private static InstanceObservation observation_from_json(Map<?, ?> map) {
    return new InstanceObservation(
        (String) map.get("deploymentName"),
        ((Number) map.get("instanceIndex")).intValue(),
        module_id_from_json((Map<?, ?>) map.get("moduleId")),
        (String) map.get("lifecycleState"),
        (Boolean) map.get("alive"),
        (Boolean) map.get("ready"));
  }

  private static Map<String, Object> observation_to_json(InstanceObservation obs) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("moduleId", module_id_to_json(obs.moduleId()));
    map.put("lifecycleState", obs.lifecycleState());
    map.put("alive", obs.alive());
    map.put("ready", obs.ready());
    return map;
  }

  private static Map<String, Object> assigned_instance_to_json(AssignedInstance instance) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("deploymentName", instance.deploymentName());
    map.put("instanceIndex", instance.instanceIndex());
    map.put("moduleId", module_id_to_json(instance.moduleId()));
    map.put("artifactPath", instance.artifactPath());
    return map;
  }

  // ---- HTTP plumbing ----

  private static String path_segment_after(HttpExchange exchange, String prefix) {
    String path = exchange.getRequestURI().getPath();
    return path.substring(prefix.length());
  }

  private static String read_body(HttpExchange exchange) throws IOException {
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

  private static void respond_json(HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respond_quietly(HttpExchange exchange, int status, String body) {
    try {
      respond(exchange, status, body);
    } catch (IOException e) {
      log.warn("failed to write error response: {}", e.getMessage());
    }
  }
}
