package com.gimle.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.gimle.core.logging.GimleLogging;
import com.gimle.core.logging.InstanceLogCloser;
import com.gimle.core.logging.InstanceMdcKeys;
import com.gimle.core.logging.PlatformFileAppender;
import com.gimle.core.protocol.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Contract test for the agent's log-serving HTTP surface -- {@code log-explorer-design.md}
 * &sect;6's per-node half. Verifies the JSON shapes {@code src/repositories/http/logs.ts} and
 * {@code gimle-cli}'s {@code LogsCommand} both need.
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
    if (platformAppender == null) {
      platformAppender =
          GimleLogging.attachPlatformFileAppender(logRoot.resolve("agent-platform.log"));
    }
    logger.info(message);
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
    InstanceLogCloser closer =
        GimleLogging.attachInstanceSiftingAppender(
            logRoot.resolve("workers").resolve(workerKey).resolve("instances"));
    MDC.put(InstanceMdcKeys.DEPLOYMENT_NAME, deploymentName);
    MDC.put(InstanceMdcKeys.INSTANCE_INDEX, Integer.toString(instanceIndex));
    root.info(message);
    MDC.clear();
    closer.closeInstance(deploymentName, instanceIndex);
    root.detachAppender((Appender<ILoggingEvent>) closer);
  }

  private Map<String, Object> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(
        200, response.statusCode(), "unexpected status for " + path + ": " + response.body());
    return Json.asObject(Json.parse(response.body()));
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
}
