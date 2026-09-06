package com.gimle.andvari;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * What a replica does while a peer stays unreachable: it keeps syncing, and it says what actually
 * went wrong rather than reporting the failure as the literal text {@code null}.
 */
class AndvariPeerSyncResilienceTest {

  /**
   * The {@code .invalid} TLD is reserved as never-resolvable, so this fails at name resolution
   * rather than at connect -- which is the point: that is the failure the JDK HTTP client reports
   * as a {@code ConnectException} carrying no message of its own.
   */
  private static final String UNRESOLVABLE_PEER = "andvari-peer-sync.invalid";

  @TempDir Path tempDir;

  @Test
  @Timeout(60)
  void an_unresolvable_peer_is_logged_with_its_real_cause_and_never_wedges_the_syncer()
      throws Exception {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AndvariPeerSync.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ArtifactStore store = new ArtifactStore(tempDir.resolve("data"));
      try (HttpClient httpClient = HttpClient.newHttpClient();
          AndvariPeerSync peerSync =
              new AndvariPeerSync(
                  store,
                  List.of(URI.create("http://" + UNRESOLVABLE_PEER + ":9")),
                  httpClient,
                  Duration.ofDays(1))) {
        // Three passes back to back: a sustained failure has to leave the syncer as usable as it
        // was before the first one.
        assertEquals(0, peerSync.sync());
        assertEquals(0, peerSync.sync());
        assertEquals(0, peerSync.sync());
      }
    } finally {
      logger.detachAppender(appender);
    }

    List<String> messages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).distinct().toList();
    assertFalse(messages.isEmpty(), "the failed passes should have logged something");
    assertTrue(
        messages.stream().noneMatch(message -> message.endsWith("failed: null")),
        "a failure carrying no message of its own must not be reported as the literal text null: "
            + messages);
    assertTrue(
        messages.stream()
            .anyMatch(
                message -> message.contains("Exception") && message.contains(UNRESOLVABLE_PEER)),
        "the log line should name both the peer and the failure that made it unreachable: "
            + messages);
  }

  @Test
  @Timeout(60)
  void a_peer_whose_downloads_keep_failing_leaves_this_replica_able_to_sync_again()
      throws Exception {
    HttpServer peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    peer.createContext("/artifacts", exchange -> respond(exchange, 200, "[\"com.example.app\"]"));
    peer.createContext(
        "/artifacts/com.example.app",
        exchange -> {
          if ("/artifacts/com.example.app".equals(exchange.getRequestURI().getPath())) {
            respond(
                exchange,
                200,
                "{\"versions\":[{\"version\":\"1.0.0\",\"sha256\":\"deadbeef\",\"kind\":\"JAR\"}]}");
            return;
          }
          // Only the download fails, so every pass gets all the way to the streaming transfer --
          // the deepest point a failing pass can reach.
          respond(exchange, 500, "boom");
        });
    peer.start();
    try {
      ArtifactStore store = new ArtifactStore(tempDir.resolve("data"));
      URI peerUri = URI.create("http://127.0.0.1:" + peer.getAddress().getPort());
      try (HttpClient httpClient = HttpClient.newHttpClient();
          AndvariPeerSync peerSync =
              new AndvariPeerSync(store, List.of(peerUri), httpClient, Duration.ofDays(1))) {
        for (int pass = 0; pass < 20; pass++) {
          assertEquals(0, peerSync.sync(), "pass " + pass + " should pull nothing and not throw");
        }
        assertTrue(store.moduleIds().isEmpty(), "a failed download must not commit anything");
      }
    } finally {
      peer.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
