package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client half of request idempotency: every mutating call identifies itself, distinctly per
 * operation, and a write whose answer never arrives is reported as an unknown outcome naming that
 * identifier rather than as a failure.
 */
class ControlPlaneClientRequestIdTest {

  private static final String REQUEST_ID_HEADER = "X-Gimle-Request-Id";
  private static final Pattern SERVER_ACCEPTED_FORM = Pattern.compile("[A-Za-z0-9._-]{8,128}");

  private HttpServer server;
  private final List<String> seenRequestIds = new CopyOnWriteArrayList<>();
  private ControlPlaneClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seenRequestIds.add(
              String.valueOf(exchange.getRequestHeaders().getFirst(REQUEST_ID_HEADER)));
          exchange.getRequestBody().readAllBytes();
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
          exchange.close();
        });
    server.start();
    client = new ControlPlaneClient("127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void every_mutating_verb_sends_a_request_id_the_server_would_accept() {
    client.put("/deployments/orders", "kind: Deployment\n");
    client.post("/deployments/orders/rollback", "{}");
    client.patch("/deployments/orders", "{}");
    client.delete("/deployments/orders");

    assertEquals(4, seenRequestIds.size());
    for (String requestId : seenRequestIds) {
      assertTrue(
          SERVER_ACCEPTED_FORM.matcher(requestId).matches(),
          "the server rejects anything outside its own validation: " + requestId);
    }
  }

  @Test
  void two_separate_operations_get_two_separate_request_ids() {
    client.post("/deployments/orders/rollback", "{}");
    client.post("/deployments/orders/rollback", "{}");

    assertNotEquals(
        seenRequestIds.get(0),
        seenRequestIds.get(1),
        "two deliberate rollbacks are two logical operations, not one retried");
  }

  @Test
  void a_read_carries_no_request_id_at_all() {
    client.get("/deployments");

    assertEquals("null", seenRequestIds.get(0), "a GET changes nothing and needs no receipt");
  }

  /**
   * The message an operator actually sees when a write's answer is lost. It must not claim the
   * write failed -- it may well have committed -- and it must name the id that settles the
   * question.
   */
  @Test
  void a_lost_answer_is_reported_as_an_unknown_outcome_naming_the_request_id() {
    URI baseUri = URI.create("http://control-plane.example:8443");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://control-plane.example:8443/deployments/orders"))
            .header(REQUEST_ID_HEADER, "req-abcdef01")
            .PUT(HttpRequest.BodyPublishers.ofString("kind: Deployment\n"))
            .build();

    CliException failure =
        ControlPlaneClient.unknownOutcome(
            baseUri, request, new HttpTimeoutException("request timed out"));

    String message = String.valueOf(failure.getMessage());
    assertTrue(message.contains("outcome of this PUT /deployments/orders is unknown"), message);
    assertTrue(message.contains("may already have been applied"), message);
    assertTrue(message.contains("req-abcdef01"), message);
    assertNull(
        find(message, "could not reach"),
        "a timeout is not an unreachable server, and must not be described as one: " + message);
  }

  private static String find(String haystack, String needle) {
    return haystack.contains(needle) ? needle : null;
  }
}
