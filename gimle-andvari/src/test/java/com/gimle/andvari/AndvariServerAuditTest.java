package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gimle.andvari.testsupport.InProcessStore;
import com.gimle.core.protocol.AuditEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.slf4j.LoggerFactory;

/**
 * The audit trail a push and a delete leave behind on a plaintext (no-mTLS) listener -- the mode a
 * single-machine cluster actually runs in, and the one where this server has no caller identity to
 * authorize. Both halves have to be there: the tailable line an operator greps for, and the durable
 * event, each naming the coordinate that was written or removed.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("gimle-andvari-server-http")
class AndvariServerAuditTest {

  @TempDir Path tempDir;

  private InProcessStore store;
  private AndvariServer server;
  private final HttpClient client = HttpClient.newHttpClient();
  private String baseUrl;
  private ch.qos.logback.classic.Logger auditLogger;
  private ListAppender<ILoggingEvent> auditAppender;

  @BeforeEach
  void setUp() throws Exception {
    store = InProcessStore.start(tempDir.resolve("store"));
    server = new AndvariServer(store.client(), 0, tempDir.resolve("data"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.port();
    auditLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.gimle.andvari.audit");
    auditAppender = new ListAppender<>();
    auditAppender.start();
    auditLogger.addAppender(auditAppender);
  }

  @AfterEach
  void tearDown() {
    auditLogger.detachAppender(auditAppender);
    server.close();
    store.close();
  }

  @Test
  @Timeout(30)
  void a_push_and_a_delete_are_both_logged_and_durably_recorded_against_their_coordinate()
      throws Exception {
    assertEquals(200, put("/artifacts/com.example.app/1.4.2", "jar-bytes").statusCode());
    assertEquals(200, delete("/artifacts/com.example.app/1.4.2").statusCode());

    List<String> lines =
        auditAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertTrue(
        lines.stream().anyMatch(line -> line.contains("verb=WRITE") && line.contains(coordinate())),
        "a push must leave a log line naming the coordinate it stored: " + lines);
    assertTrue(
        lines.stream()
            .anyMatch(line -> line.contains("verb=DELETE") && line.contains(coordinate())),
        "a delete must leave a log line naming the coordinate it removed: " + lines);

    List<AuditEvent> recorded =
        store
            .store()
            .listAuditEvents(
                Optional.empty(), Optional.of("ARTIFACT"), Optional.empty(), Optional.empty());
    assertTrue(
        recorded.stream()
            .anyMatch(
                event ->
                    "WRITE".equals(event.verb())
                        && event.targetId().equals(Optional.of(coordinate()))),
        "the durable record of a push must name the artifact, not just that one happened: "
            + recorded);
    assertTrue(
        recorded.stream()
            .anyMatch(
                event ->
                    "DELETE".equals(event.verb())
                        && event.targetId().equals(Optional.of(coordinate()))),
        "the durable record of a delete must name the artifact it removed: " + recorded);
  }

  private static String coordinate() {
    return "com.example.app:1.4.2";
  }

  private HttpResponse<String> put(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> delete(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
