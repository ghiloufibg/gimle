package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.logging.InstanceSiftingFileAppender;
import com.gimle.core.logging.PlatformFileAppender;
import com.gimle.core.module.VolumeRequest;
import com.gimle.core.protocol.Json;
import com.gimle.os.localdisk.LocalDiskVolumeManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Contract test for the agent's log-serving HTTP surface's per-node half. Verifies the JSON shapes
 * {@code src/repositories/http/logs.ts} and {@code gimle-cli}'s {@code LogsCommand} both need.
 *
 * <p><b>{@code follow=true} is deliberately not exercised here.</b> Investigated at length while
 * building this: a genuinely long-blocking/chunked HTTP read against this server only works
 * reliably when the client and server are separate JVM processes -- once any {@code
 * java.net.http.HttpClient} instance exists in the *same* JVM as this test's own {@code
 * AgentLogServer}, blocking socket reads for a chunked response stop receiving any bytes at all,
 * reproduced multiple times with both {@code HttpClient} and a raw {@code Socket} as the reader.
 * That's a same-process test artifact, not a real deployment concern -- the agent, control plane,
 * and CLI are always separate processes in actual use -- so {@code follow=true} is verified for
 * real via the local-dev runbook (a real {@code ControlPlaneMain} + {@code AgentMain} + a real
 * browser/CLI, genuinely different processes), not a same-JVM unit test.
 */
class AgentLogServerTest {

  @TempDir Path logRoot;

  private final Logger logger =
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("test.agentlogserver");

  private AgentLogServer server;
  private PlatformFileAppender platformAppender;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  @AfterEach
  void tearDown() {
    MDC.clear();
    if (server != null) {
      server.close();
    }
    if (platformAppender != null) {
      platformAppender.stop();
    }
  }

  private void startServer() throws IOException {
    server = new AgentLogServer(logRoot, 0);
    server.start();
  }

  private void writePlatformLine(String message) {
    writePlatformLine(Level.INFO, message);
  }

  private void writePlatformLine(Level level, String message) {
    if (platformAppender == null) {
      platformAppender =
          GimleLogging.attachPlatformFileAppender(logRoot.resolve("agent-platform.log"));
    }
    // Explicit, so a DEBUG line the filtering tests depend on isn't dropped by whatever level
    // this test logger inherits from the surrounding configuration.
    logger.setLevel(Level.TRACE);
    switch (level.toInt()) {
      case Level.DEBUG_INT -> logger.debug(message);
      case Level.WARN_INT -> logger.warn(message);
      case Level.ERROR_INT -> logger.error(message);
      default -> logger.info(message);
    }
  }

  /** The four levels the filtering tests below span, one line each, oldest first. */
  private static final List<String> MIXED_LEVEL_MESSAGES =
      List.of(
          "cache warmed",
          "agent registered with control plane",
          "heartbeat delayed by 4s",
          "downstream call timed out");

  private void writeMixedLevelPlatformLines() {
    writePlatformLine(Level.DEBUG, MIXED_LEVEL_MESSAGES.get(0));
    writePlatformLine(Level.INFO, MIXED_LEVEL_MESSAGES.get(1));
    writePlatformLine(Level.WARN, MIXED_LEVEL_MESSAGES.get(2));
    writePlatformLine(Level.ERROR, MIXED_LEVEL_MESSAGES.get(3));
  }

  /**
   * These lines are written through the real root logger, which every other test class in this JVM
   * also logs into, so a concurrently-running class's own output lands in the same capture. Keeping
   * only this fixture's own messages makes the level assertions depend on the filter under test
   * rather than on what else happened to log at the same moment.
   */
  private static List<String> onlyFixtureLines(List<String> messages) {
    return messages.stream().filter(MIXED_LEVEL_MESSAGES::contains).toList();
  }

  private static List<String> messagesOf(Map<String, Object> page) {
    return Json.asObjectList(page.get("lines")).stream()
        .map(l -> String.valueOf(l.get("message")))
        .toList();
  }

  /**
   * Mirrors AgentMain's real per-worker scoping: each instance gets its own worker JVM, whose
   * {@code -Dgimle.log.root} points at {@code workers/<deploymentName>#<instanceIndex>/}. A real
   * worker JVM attaches exactly one such appender for its whole lifetime; this test simulates two
   * separate worker JVMs within one test JVM by attaching, writing, then fully detaching each
   * appender before the next -- attaching both at once would leave them both listening on the
   * shared root logger simultaneously and each would sift (and lock a file for) every line,
   * including ones meant for the other simulated worker.
   */
  private void writeInstanceLine(String deploymentName, int instanceIndex, String message) {
    String workerKey = deploymentName + "#" + instanceIndex;
    Logger root =
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    // GimleLogging.attachInstanceSiftingAppender declares InstanceLogCloser (its lifecycle
    // contract), but its concrete return value is always an InstanceSiftingFileAppender -- a
    // non-generic Appender<ILoggingEvent> subclass by construction, so naming that concrete type
    // here lets detachAppender take it directly, with no unchecked cast to the generic interface.
    InstanceSiftingFileAppender appender =
        (InstanceSiftingFileAppender)
            GimleLogging.attachInstanceSiftingAppender(
                logRoot.resolve("workers").resolve(workerKey).resolve("instances"));
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, deploymentName);
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, Integer.toString(instanceIndex));
    root.info(message);
    MDC.clear();
    appender.closeInstance(deploymentName, instanceIndex);
    root.detachAppender(appender);
  }

  private Map<String, Object> get(String path) throws Exception {
    HttpResponse<String> response = getRaw(path);
    assertEquals(
        200, response.statusCode(), "unexpected status for " + path + ": " + response.body());
    return Json.asObject(Json.parse(response.body()));
  }

  private List<Object> getJsonArray(String path) throws Exception {
    HttpResponse<String> response = getRaw(path);
    assertEquals(
        200, response.statusCode(), "unexpected status for " + path + ": " + response.body());
    return Json.asArray(Json.parse(response.body()));
  }

  // ---- /fabric-endpoints/{deploymentName}/{instanceIndex} ----

  private void startWithFabricEndpoint(
      String key, java.util.Optional<InstanceFabricEndpoint> endpoint) throws IOException {
    server =
        new AgentLogServer(
            logRoot,
            0,
            java.util.function.Function.identity(),
            null,
            Set::of,
            requested ->
                requested.equals(key)
                    ? endpoint
                    : java.util.Optional.<InstanceFabricEndpoint>empty());
    server.start();
  }

  @Test
  void a_supervised_instance_reports_the_fabric_address_its_worker_bound() throws Exception {
    startWithFabricEndpoint(
        "greeter-provider#2",
        java.util.Optional.of(
            new InstanceFabricEndpoint(
                java.util.Optional.of("worker-4821"),
                java.util.Optional.of(
                    new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), 41234)),
                "/tmp/gimle-fabric-4821.sock")));

    HttpResponse<String> response = getRaw("/fabric-endpoints/greeter-provider/2");

    assertEquals(200, response.statusCode());
    Map<String, Object> body = Json.asObject(Json.parse(response.body()));
    assertEquals("greeter-provider", body.get("deploymentName"));
    assertEquals(2L, body.get("instanceIndex"));
    assertEquals("worker-4821", body.get("workerId"));
    // The dialable form is what a caller actually needs; host and port travel alongside it so a
    // client never has to parse the joined string back apart.
    assertEquals("127.0.0.1:41234", body.get("tcpAddress"));
    assertEquals(41234L, body.get("tcpPort"));
    assertEquals("/tmp/gimle-fabric-4821.sock", body.get("udsPath"));
  }

  @Test
  void a_worker_that_bound_no_domain_socket_omits_the_uds_path() throws Exception {
    startWithFabricEndpoint(
        "greeter-provider#0",
        java.util.Optional.of(
            new InstanceFabricEndpoint(
                java.util.Optional.of("worker-7"),
                java.util.Optional.of(
                    new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), 5000)),
                "")));

    Map<String, Object> body =
        Json.asObject(Json.parse(getRaw("/fabric-endpoints/greeter-provider/0").body()));

    assertFalse(body.containsKey("udsPath"));
  }

  @Test
  void an_instance_this_node_does_not_supervise_is_a_404() throws Exception {
    startWithFabricEndpoint("greeter-provider#0", java.util.Optional.empty());

    assertEquals(404, getRaw("/fabric-endpoints/greeter-provider/0").statusCode());
  }

  @Test
  void an_instance_whose_worker_has_not_handshaked_yet_is_a_409_not_a_404() throws Exception {
    // The distinction is the point: 404 means "look on another node", 409 means "this is the right
    // node, the instance is still coming up" -- so a caller knows to retry rather than re-resolve.
    startWithFabricEndpoint(
        "greeter-provider#0",
        java.util.Optional.of(
            new InstanceFabricEndpoint(
                java.util.Optional.empty(), java.util.Optional.empty(), "")));

    assertEquals(409, getRaw("/fabric-endpoints/greeter-provider/0").statusCode());
  }

  @Test
  void a_malformed_fabric_endpoint_path_is_rejected() throws Exception {
    startWithFabricEndpoint("greeter-provider#0", java.util.Optional.empty());

    assertEquals(400, getRaw("/fabric-endpoints/greeter-provider").statusCode());
    assertEquals(400, getRaw("/fabric-endpoints/greeter-provider/not-a-number").statusCode());
    // A traversal attempt must never reach the resolver, the same guard the log routes apply.
    assertEquals(400, getRaw("/fabric-endpoints/..%2Fetc/0").statusCode());
  }

  private HttpResponse<String> getRaw(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void health_reports_up_with_no_configuration_needed() throws Exception {
    startServer();

    Map<String, Object> body = get("/health");

    assertEquals("UP", body.get("status"));
  }

  @Test
  void health_rejects_a_non_get_method() throws Exception {
    startServer();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertEquals(405, response.statusCode());
  }

  @Test
  void volumes_are_listed_with_usage_and_in_use_flags_and_destroy_respects_them() throws Exception {
    Path dataRoot = logRoot.resolve("data");
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(dataRoot);
    Path held =
        volumeManager.hostPath(
            volumeManager.allocate(
                java.util.Optional.empty(), "sessions", 0, "data", new VolumeRequest(64)));
    Files.writeString(held.resolve("live.db"), "12345");
    Path orphan =
        volumeManager.hostPath(
            volumeManager.allocate(
                java.util.Optional.empty(), "sessions", 1, "data", new VolumeRequest(64)));
    Files.writeString(orphan.resolve("old.db"), "123");
    server =
        new AgentLogServer(
            logRoot,
            0,
            java.util.function.Function.identity(),
            volumeManager,
            () -> Set.of(AgentLogServer.volumeKey(java.util.Optional.empty(), "sessions", 0)));
    server.start();

    List<Object> listed = getJsonArray("/volumes");
    List<Map<String, Object>> entries = listed.stream().map(Json::asObject).toList();
    assertEquals(2, entries.size());
    Map<String, Object> heldEntry =
        entries.stream()
            .filter(e -> ((Number) e.get("instanceIndex")).intValue() == 0)
            .findFirst()
            .orElseThrow();
    assertEquals("sessions", heldEntry.get("statefulSet"));
    assertEquals(5L, ((Number) heldEntry.get("usedBytes")).longValue());
    assertEquals(Boolean.TRUE, heldEntry.get("inUse"));
    Map<String, Object> orphanEntry =
        entries.stream()
            .filter(e -> ((Number) e.get("instanceIndex")).intValue() == 1)
            .findFirst()
            .orElseThrow();
    assertEquals(Boolean.FALSE, orphanEntry.get("inUse"));

    // Destroying the in-use volume is refused; destroying the orphan actually deletes it.
    assertEquals(409, delete("/volumes/sessions/0").statusCode());
    assertTrue(Files.exists(held));
    assertEquals(200, delete("/volumes/sessions/1").statusCode());
    assertFalse(Files.exists(orphan));
    assertEquals(400, delete("/volumes/..%5Cpwn/1").statusCode());
  }

  /**
   * A destroy naming a tenant this node holds no such volume for must not report success. The
   * volume directory tree is keyed by tenant, so answering 200 for a coordinate with nothing on
   * disk would tell an operator their reclaim happened when they had in fact addressed the wrong
   * tenant -- and left the volume they meant to reclaim untouched.
   */
  @Test
  void destroying_a_volume_under_the_wrong_tenant_reports_404_and_leaves_it_on_disk()
      throws Exception {
    Path dataRoot = logRoot.resolve("data");
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(dataRoot);
    Path tenanted =
        volumeManager.hostPath(
            volumeManager.allocate(
                java.util.Optional.of("acme"), "sessions", 0, "data", new VolumeRequest(64)));
    Files.writeString(tenanted.resolve("live.db"), "12345");
    server =
        new AgentLogServer(
            logRoot, 0, java.util.function.Function.identity(), volumeManager, Set::of);
    server.start();

    assertEquals(404, delete("/volumes/sessions/0").statusCode());
    assertEquals(404, delete("/volumes/sessions/0?tenant=globex").statusCode());
    assertTrue(Files.exists(tenanted));

    assertEquals(200, delete("/volumes/sessions/0?tenant=acme").statusCode());
    assertFalse(Files.exists(tenanted));
  }

  /**
   * A blank {@code ?tenant=} means the untenanted namespace, the same as omitting it -- a real
   * tenant id is never blank, so the two spellings cannot collide, and a caller that must always
   * send the parameter still has a way to say "untenanted".
   */
  @Test
  void a_blank_tenant_parameter_addresses_the_untenanted_volume_the_same_as_omitting_it()
      throws Exception {
    Path dataRoot = logRoot.resolve("data");
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(dataRoot);
    Path untenanted =
        volumeManager.hostPath(
            volumeManager.allocate(
                java.util.Optional.empty(), "sessions", 0, "data", new VolumeRequest(64)));
    Files.writeString(untenanted.resolve("old.db"), "123");
    server =
        new AgentLogServer(
            logRoot, 0, java.util.function.Function.identity(), volumeManager, Set::of);
    server.start();

    assertEquals(200, delete("/volumes/sessions/0?tenant=").statusCode());
    assertFalse(Files.exists(untenanted));
  }

  /** A second destroy of the same volume is a 404, not a second "destroyed" for the same data. */
  @Test
  void destroying_an_already_destroyed_volume_reports_404_rather_than_success() throws Exception {
    Path dataRoot = logRoot.resolve("data");
    LocalDiskVolumeManager volumeManager = new LocalDiskVolumeManager(dataRoot);
    volumeManager.allocate(
        java.util.Optional.empty(), "sessions", 0, "data", new VolumeRequest(64));
    server =
        new AgentLogServer(
            logRoot, 0, java.util.function.Function.identity(), volumeManager, Set::of);
    server.start();

    assertEquals(200, delete("/volumes/sessions/0").statusCode());
    assertEquals(404, delete("/volumes/sessions/0").statusCode());
  }

  private HttpResponse<String> delete(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .DELETE()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void node_platform_logs_have_the_shape_the_console_and_cli_need() throws Exception {
    startServer();
    writePlatformLine("agent booted");
    writePlatformLine("agent registered with control plane");

    Map<String, Object> page = get("/logs/nodes/node-a?category=PLATFORM&limit=10");
    List<Map<String, Object>> lines = Json.asObjectList(page.get("lines"));
    assertTrue(lines.size() >= 2, "expected at least the two written lines");
    Map<String, Object> first = lines.get(0);
    assertTrue(first.containsKey("timestamp"));
    assertTrue(first.containsKey("level"));
    assertTrue(first.containsKey("message"));
    assertEquals("PLATFORM", first.get("category"));
    assertTrue(
        lines.stream()
            .anyMatch(l -> "agent registered with control plane".equals(l.get("message"))));
  }

  @Test
  void instance_application_logs_are_scoped_to_the_right_deployment_and_index() throws Exception {
    startServer();
    writeInstanceLine("orders-service", 0, "instance 0 line");
    writeInstanceLine("orders-service", 1, "instance 1 line");

    Map<String, Object> page =
        get("/logs/instances/orders-service/0?category=APPLICATION&limit=10");
    List<Map<String, Object>> lines = Json.asObjectList(page.get("lines"));
    assertTrue(lines.stream().anyMatch(l -> "instance 0 line".equals(l.get("message"))));
    assertFalse(lines.stream().anyMatch(l -> "instance 1 line".equals(l.get("message"))));
    assertEquals("APPLICATION", lines.get(0).get("category"));
  }

  @Test
  void instance_logs_reject_a_deployment_name_containing_a_path_separator() throws Exception {
    startServer();
    HttpResponse<String> response = getRaw("/logs/instances/pwn%5C..%5Cetc/0");
    assertEquals(400, response.statusCode());
  }

  @Test
  void instance_logs_reject_a_deployment_name_that_would_escape_the_log_root() throws Exception {
    startServer();
    // Mirrors a live-proven path-traversal probe: a backslash-encoded ".." pair composes, on
    // Windows, into a path that escapes logRoot entirely once resolved -- rejected by the
    // DEPLOYMENT_NAME allow-list before any Path.resolve happens.
    HttpResponse<String> response = getRaw("/logs/instances/..%5C..%5Cpwn/0");
    assertEquals(400, response.statusCode());
  }

  @Test
  void crash_dumps_are_listed_from_the_right_worker_directory_only() throws Exception {
    startServer();
    Path workerDir = logRoot.resolve("workers").resolve("orders-service#0");
    Files.createDirectories(workerDir);
    Files.writeString(workerDir.resolve("hs_err_pid111.log"), "crash one");
    Files.writeString(workerDir.resolve("hs_err_pid222.log"), "crash two, a bit longer");
    Files.writeString(workerDir.resolve("worker-platform.log"), "not a crash dump");

    List<Object> dumps = getJsonArray("/logs/instances/orders-service/0/crashdumps");
    List<Map<String, Object>> entries = dumps.stream().map(Json::asObject).toList();

    assertEquals(
        2, entries.size(), "expected exactly the two hs_err_pid*.log files, not the platform log");
    assertTrue(entries.stream().anyMatch(e -> "hs_err_pid111.log".equals(e.get("name"))));
    assertTrue(entries.stream().anyMatch(e -> "hs_err_pid222.log".equals(e.get("name"))));
    Map<String, Object> first =
        entries.stream().filter(e -> "hs_err_pid111.log".equals(e.get("name"))).findFirst().get();
    assertEquals(((Number) first.get("sizeBytes")).longValue(), "crash one".getBytes().length);
    assertTrue(first.containsKey("lastModified"));
  }

  @Test
  void crash_dumps_list_is_empty_when_the_worker_never_crashed() throws Exception {
    startServer();
    List<Object> dumps = getJsonArray("/logs/instances/never-crashed/0/crashdumps");
    assertTrue(dumps.isEmpty());
  }

  @Test
  void a_crash_dump_is_fetched_with_its_exact_content_and_a_plain_text_content_type()
      throws Exception {
    startServer();
    Path workerDir = logRoot.resolve("workers").resolve("orders-service#0");
    Files.createDirectories(workerDir);
    String content = "# A fatal error has been detected...\nmore crash detail\n";
    Files.writeString(workerDir.resolve("hs_err_pid333.log"), content);

    HttpResponse<String> response =
        getRaw("/logs/instances/orders-service/0/crashdumps/hs_err_pid333.log");

    assertEquals(200, response.statusCode());
    assertEquals(content, response.body());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
  }

  @Test
  void crash_dump_fetch_rejects_a_filename_that_does_not_match_the_expected_pattern()
      throws Exception {
    startServer();
    Path workerDir = logRoot.resolve("workers").resolve("orders-service#0");
    Files.createDirectories(workerDir);
    // A real file sitting right next to a real crash dump, in the exact same directory -- proves
    // the guard is a strict filename allow-list, not just "does the file happen to exist."
    Files.writeString(workerDir.resolve("worker-platform.log"), "not a crash dump");

    HttpResponse<String> response =
        getRaw("/logs/instances/orders-service/0/crashdumps/worker-platform.log");

    assertEquals(400, response.statusCode());
  }

  @Test
  void a_level_filter_keeps_that_level_and_every_level_above_it() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    List<String> messages = messagesOf(get("/logs/nodes/node-a?category=PLATFORM&level=WARN"));

    assertEquals(
        List.of("heartbeat delayed by 4s", "downstream call timed out"),
        onlyFixtureLines(messages));
  }

  @Test
  void a_level_filter_at_the_lowest_threshold_keeps_every_ranked_line() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    List<String> messages = messagesOf(get("/logs/nodes/node-a?category=PLATFORM&level=trace"));

    assertEquals(
        MIXED_LEVEL_MESSAGES,
        onlyFixtureLines(messages),
        "TRACE is the floor -- nothing structured ranks below it");
  }

  @Test
  void a_text_filter_keeps_only_lines_carrying_that_substring() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    List<String> messages =
        messagesOf(get("/logs/nodes/node-a?category=PLATFORM&contains=HEARTBEAT"));

    assertEquals(List.of("heartbeat delayed by 4s"), messages);
  }

  @Test
  void level_and_text_filters_apply_together_and_alongside_the_since_cursor() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    List<String> messages =
        messagesOf(
            get(
                "/logs/nodes/node-a?category=PLATFORM&since=2000-01-01T00:00:00Z"
                    + "&level=WARN&contains=timed"));

    assertEquals(List.of("downstream call timed out"), messages);
  }

  @Test
  void a_filter_matching_nothing_answers_with_an_empty_page_not_an_error() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    Map<String, Object> page =
        get("/logs/nodes/node-a?category=PLATFORM&contains=no%20such%20text%20anywhere");

    assertTrue(Json.asObjectList(page.get("lines")).isEmpty());
    // The paging shape survives a zero-match query, so a caller renders "nothing matched" rather
    // than failing to parse a differently-shaped body.
    assertTrue(page.containsKey("olderCursor"));
    assertTrue(page.containsKey("newerCursor"));
  }

  @Test
  void an_unrecognized_level_is_rejected_rather_than_silently_ignored() throws Exception {
    startServer();
    writeMixedLevelPlatformLines();

    HttpResponse<String> response = getRaw("/logs/nodes/node-a?category=PLATFORM&level=SEVERE");

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("SEVERE"), response.body());
  }

  @Test
  void instance_logs_apply_the_same_filter_the_node_routes_do() throws Exception {
    startServer();
    writeInstanceLine("orders-service", 0, "instance 0 line");
    writeInstanceLine("orders-service", 0, "instance 0 second line");

    List<String> messages =
        messagesOf(get("/logs/instances/orders-service/0?category=APPLICATION&contains=second"));

    assertEquals(List.of("instance 0 second line"), messages);
  }
}
