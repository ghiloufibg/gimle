package com.gimle.hilmir.release;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An in-memory stand-in for just the control-plane HTTP surface the release verbs call: {@code
 * /tenants/*}, {@code /config/*}, {@code /secrets/*}, and the five workload kinds' own {@code
 * PUT}/{@code GET}/{@code DELETE} -- enough to exercise a verb's real HTTP calls end to end, the
 * same {@code com.sun.net.httpserver.HttpServer} stub shape {@code gimle-agent}'s own {@code
 * AgentMuninnShippingTest} already establishes for this exact situation.
 *
 * <p>{@link #handleConfigList} deliberately reproduces the real control plane's own {@code
 * isFafnirManagedSecretKey}-style filter (see {@code ApiServer}): any key whose text after its last
 * {@code @} character reads {@code meta} or is all digits is dropped from a list response. This
 * proves {@link ReleaseLedger}'s {@code .}-delimited key naming actually survives that filter --
 * without reproducing it here, a ledger test could pass against a stub that's more permissive than
 * the real thing.
 */
final class FakeControlPlane implements AutoCloseable {

  private static final Map<String, String> KIND_PREFIXES =
      Map.of(
          "Deployment", "/deployments/",
          "Job", "/jobs/",
          "CronJob", "/cronjobs/",
          "DaemonSet", "/daemonsets/",
          "StatefulSet", "/statefulsets/");

  record Recorded(String method, String path, String body) {}

  private record ConfigValue(String value, boolean encrypted) {}

  static final class WorkloadRecord {
    String yaml;
    List<Map<String, Object>> instances = new ArrayList<>();
    String phase = "RUNNING";
    int pollsRemaining;
    List<Map<String, Object>> instancesWhenReady;

    WorkloadRecord(String yaml) {
      this.yaml = yaml;
    }
  }

  private final HttpServer server;
  private final Map<String, Map<String, Object>> tenants = new LinkedHashMap<>();
  private final Map<String, Map<String, ConfigValue>> config = new LinkedHashMap<>();
  private final Map<String, Map<String, WorkloadRecord>> workloads = new LinkedHashMap<>();
  final List<Recorded> requests = new CopyOnWriteArrayList<>();

  FakeControlPlane() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::dispatch);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  String address() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  boolean hasTenant(String id) {
    return tenants.containsKey(id);
  }

  boolean hasWorkload(String kind, String name) {
    Map<String, WorkloadRecord> byName = workloads.get(kind);
    return byName != null && byName.containsKey(name);
  }

  String configValue(String tenant, String key) {
    ConfigValue value = config.getOrDefault(tenant, Map.of()).get(key);
    return value == null ? null : value.value();
  }

  /**
   * Directly sets a workload's live instance status, for a {@code --wait}/status test to observe
   * immediately.
   */
  void setInstances(String kind, String name, List<Map<String, Object>> instances) {
    workloadRecord(kind, name).instances = instances;
  }

  void setJobPhase(String kind, String name, String phase) {
    workloadRecord(kind, name).phase = phase;
  }

  /**
   * The workload's status GETs report not-ready for {@code pollsUntilReady} calls, then switches to
   * {@code instances}.
   */
  void scheduleReadyAfterPolls(
      String kind, String name, int pollsUntilReady, List<Map<String, Object>> instances) {
    WorkloadRecord record = workloadRecord(kind, name);
    record.pollsRemaining = pollsUntilReady;
    record.instancesWhenReady = instances;
  }

  private WorkloadRecord workloadRecord(String kind, String name) {
    return workloads
        .computeIfAbsent(kind, k -> new LinkedHashMap<>())
        .computeIfAbsent(name, n -> new WorkloadRecord(""));
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getPath();
    String body;
    try (InputStream in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    requests.add(new Recorded(method, path, body));
    try {
      route(exchange, method, path, body);
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange, String method, String path, String body)
      throws IOException {
    if (path.startsWith("/tenants/")) {
      handleTenant(exchange, method, path.substring("/tenants/".length()), body);
    } else if (path.equals("/config/" + ReleaseLedger.TENANT)) {
      handleConfigList(exchange, method);
    } else if (path.startsWith("/config/")) {
      handleConfigEntry(exchange, method, path.substring("/config/".length()), body);
    } else if (path.startsWith("/secrets/")) {
      handleSecret(exchange, method);
    } else {
      handleWorkload(exchange, method, path, body);
    }
  }

  private void handleTenant(HttpExchange exchange, String method, String id, String body)
      throws IOException {
    switch (method) {
      case "PUT" -> {
        tenants.put(id, Json.asObject(Json.parse(body)));
        respond(exchange, 200, "ok");
      }
      case "DELETE" -> {
        tenants.remove(id);
        respond(exchange, 200, "ok");
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleConfigList(HttpExchange exchange, String method) throws IOException {
    if (!"GET".equals(method)) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    List<Map<String, Object>> list = new ArrayList<>();
    for (Map.Entry<String, ConfigValue> entry :
        config.getOrDefault(ReleaseLedger.TENANT, Map.of()).entrySet()) {
      if (isFafnirManagedSecretKey(entry.getKey())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", entry.getKey());
      row.put("value", entry.getValue().value());
      row.put("encrypted", entry.getValue().encrypted());
      list.add(row);
    }
    respondJson(exchange, 200, list);
  }

  private static boolean isFafnirManagedSecretKey(String key) {
    int at = key.lastIndexOf('@');
    if (at < 0 || at == key.length() - 1) {
      return false;
    }
    String suffix = key.substring(at + 1);
    return suffix.equals("meta") || suffix.chars().allMatch(Character::isDigit);
  }

  private void handleConfigEntry(HttpExchange exchange, String method, String tail, String body)
      throws IOException {
    int slash = tail.indexOf('/');
    String tenant = tail.substring(0, slash);
    String key = tail.substring(slash + 1);
    switch (method) {
      case "PUT" -> {
        Map<String, Object> parsed = Json.asObject(Json.parse(body));
        config
            .computeIfAbsent(tenant, t -> new LinkedHashMap<>())
            .put(
                key,
                new ConfigValue(
                    (String) parsed.get("value"), Boolean.TRUE.equals(parsed.get("encrypted"))));
        respond(exchange, 200, "ok");
      }
      case "DELETE" -> {
        Map<String, ConfigValue> tenantConfig = config.get(tenant);
        if (tenantConfig == null || tenantConfig.remove(key) == null) {
          respond(exchange, 404, "no such config entry: " + key);
          return;
        }
        respond(exchange, 200, "ok");
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private void handleSecret(HttpExchange exchange, String method) throws IOException {
    if (!"PUT".equals(method)) {
      respond(exchange, 405, "method not allowed");
      return;
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("version", 1);
    respondJson(exchange, 200, response);
  }

  private void handleWorkload(HttpExchange exchange, String method, String path, String body)
      throws IOException {
    for (Map.Entry<String, String> kindPrefix : KIND_PREFIXES.entrySet()) {
      String prefix = kindPrefix.getValue();
      if (path.startsWith(prefix)) {
        dispatchWorkload(
            exchange, method, kindPrefix.getKey(), path.substring(prefix.length()), body);
        return;
      }
    }
    respond(exchange, 404, "not found: " + path);
  }

  private void dispatchWorkload(
      HttpExchange exchange, String method, String kind, String name, String body)
      throws IOException {
    Map<String, WorkloadRecord> byName =
        workloads.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    switch (method) {
      case "PUT" -> {
        byName.computeIfAbsent(name, n -> new WorkloadRecord(body)).yaml = body;
        respond(exchange, 200, "ok");
      }
      case "GET" -> {
        WorkloadRecord record = byName.get(name);
        if (record == null) {
          respond(exchange, 404, "not found: " + name);
          return;
        }
        if (record.pollsRemaining > 0) {
          record.pollsRemaining--;
          if (record.pollsRemaining == 0 && record.instancesWhenReady != null) {
            record.instances = record.instancesWhenReady;
          }
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("instances", record.instances);
        status.put("phase", record.phase);
        respondJson(exchange, 200, status);
      }
      case "DELETE" -> {
        if (byName.remove(name) == null) {
          respond(exchange, 404, "not found: " + name);
          return;
        }
        respond(exchange, 200, "ok");
      }
      default -> respond(exchange, 405, "method not allowed");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void respondJson(HttpExchange exchange, int status, Object value)
      throws IOException {
    respond(exchange, status, Json.write(value));
  }
}
