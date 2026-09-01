package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code gimle logs}' own request-building and rendering, against a stub HTTP server standing in
 * for the control plane -- what matters here is the query string this command actually sends (the
 * filtering flags included) and what it prints back, not the log store on the far end, which has
 * its own tests.
 */
class LogsCommandTest {

  private HttpServer stub;
  private final List<String> receivedUris = new CopyOnWriteArrayList<>();
  private ByteArrayOutputStream outBuffer;
  private PrintStream out;
  private volatile List<Map<String, Object>> pageLines = List.of();

  @BeforeEach
  void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/logs", this::respond);
    stub.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    stub.start();
    outBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopStub() {
    stub.stop(0);
  }

  /**
   * Answers a plain GET with a JSON page, and a {@code follow=true} GET with the same lines as a
   * bounded NDJSON body -- bounded rather than open-ended so the command's read loop reaches EOF
   * and returns instead of tailing forever.
   */
  private void respond(HttpExchange exchange) throws IOException {
    receivedUris.add(exchange.getRequestURI().toString());
    boolean follow = String.valueOf(exchange.getRequestURI().getRawQuery()).contains("follow=true");
    String body;
    if (follow) {
      StringBuilder ndjson = new StringBuilder();
      for (Map<String, Object> line : pageLines) {
        ndjson.append(Json.write(line)).append('\n');
      }
      body = ndjson.toString();
    } else {
      Map<String, Object> page = new LinkedHashMap<>();
      page.put("lines", pageLines);
      page.put("olderCursor", null);
      page.put("newerCursor", null);
      body = Json.write(page);
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .add("Content-Type", follow ? "application/x-ndjson" : "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(bytes);
    }
    exchange.close();
  }

  private static Map<String, Object> line(String level, String message) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", "2026-08-10T10:00:00Z");
    line.put("level", level);
    line.put("logger", "com.example.Handler");
    line.put("message", message);
    return line;
  }

  private void run(String... args) {
    new LogsCommand(new ControlPlaneClient("127.0.0.1:" + stub.getAddress().getPort()), out)
        .run(List.of(args));
  }

  private String printed() {
    return outBuffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void a_level_and_text_filter_travel_to_the_backend_as_query_parameters() {
    pageLines = List.of(line("ERROR", "downstream call timed out"));

    run("node/node-a", "--level=WARN", "--contains=timed out");

    assertEquals(1, receivedUris.size());
    String uri = receivedUris.get(0);
    assertTrue(uri.contains("level=WARN"), uri);
    // Space-carrying search text is percent-encoded rather than spliced into the query raw.
    assertTrue(uri.contains("contains=timed+out") || uri.contains("contains=timed%20out"), uri);
    assertTrue(printed().contains("downstream call timed out"));
  }

  @Test
  void the_same_filter_flags_apply_under_follow() {
    pageLines = List.of(line("ERROR", "downstream call timed out"));

    run("instance/orders-service/0", "--follow", "--level=ERROR", "--contains=timed");

    assertEquals(1, receivedUris.size());
    String uri = receivedUris.get(0);
    assertTrue(uri.contains("follow=true"), uri);
    assertTrue(uri.contains("level=ERROR"), uri);
    assertTrue(uri.contains("contains=timed"), uri);
    assertTrue(printed().contains("downstream call timed out"));
  }

  @Test
  void no_filter_flags_means_no_filter_parameters_at_all() {
    pageLines = List.of(line("INFO", "order accepted"));

    run("controlplane");

    String uri = receivedUris.get(0);
    assertFalse(uri.contains("level="), uri);
    assertFalse(uri.contains("contains="), uri);
  }

  @Test
  void a_filtered_query_matching_nothing_prints_what_was_filtered_on_rather_than_nothing() {
    pageLines = List.of();

    run("node/node-a", "--level=ERROR", "--contains=boom");

    assertTrue(
        printed().contains("no log lines matched level >= ERROR, containing \"boom\""), printed());
  }

  @Test
  void an_unfiltered_query_returning_nothing_still_says_so() {
    pageLines = List.of();

    run("node/node-a");

    assertEquals("(no log lines)", printed().strip());
  }

  @Test
  void an_unrecognized_level_fails_before_any_request_is_sent() {
    CliException thrown =
        assertThrows(CliException.class, () -> run("node/node-a", "--level=SEVERE"));

    assertTrue(thrown.getMessage().contains("SEVERE"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("ERROR"), thrown.getMessage());
    assertTrue(receivedUris.isEmpty(), "a bad level must not cost a round trip");
  }

  @Test
  void the_usage_text_documents_both_filtering_flags() {
    assertTrue(LogsCommand.usage().contains("--level"));
    assertTrue(LogsCommand.usage().contains("--contains"));
  }
}
