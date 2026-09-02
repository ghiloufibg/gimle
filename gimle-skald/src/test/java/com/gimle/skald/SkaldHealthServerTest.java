package com.gimle.skald;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.skald.directory.CachingServiceDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The liveness/readiness split that makes a deployed Skald probe-able at all: the platform's vessel
 * probes speak TCP and HTTP, and neither can reach a DNS-over-UDP responder.
 */
class SkaldHealthServerTest {

  private final HttpClient client = HttpClient.newHttpClient();
  private SkaldHealthServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  /** A clock the test advances by hand, so staleness is exact rather than timing-dependent. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void a_freshly_refreshed_directory_is_both_alive_and_ready() throws Exception {
    MovableClock clock = new MovableClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    directory.replaceAll(Map.of());
    server = new SkaldHealthServer(directory, 0, Duration.ofSeconds(30));

    assertEquals(200, get("/health").statusCode());
    assertEquals(200, get("/ready").statusCode());
  }

  @Test
  void a_stale_directory_is_unready_but_still_alive() throws Exception {
    // The distinction is the whole point: a Skald whose control-plane polls are failing is not
    // broken, it is answering from old data. Restarting it would fix nothing, so liveness must
    // stay green while readiness takes it out of the Service's endpoint set.
    MovableClock clock = new MovableClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    directory.replaceAll(Map.of());
    server = new SkaldHealthServer(directory, 0, Duration.ofSeconds(30));

    clock.advance(Duration.ofSeconds(31));

    assertEquals(200, get("/health").statusCode(), "a stale responder is still alive");
    HttpResponse<String> ready = get("/ready");
    assertEquals(503, ready.statusCode());
    assertTrue(ready.body().contains("threshold"), "the reason must name the threshold");
  }

  @Test
  void a_directory_that_has_never_polled_successfully_is_not_ready() throws Exception {
    // Startup, before the first poll lands: answering queries from an empty directory would be
    // worse than answering none, so readiness must not open until real data has arrived.
    MovableClock clock = new MovableClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    server = new SkaldHealthServer(directory, 0, Duration.ZERO);

    clock.advance(Duration.ofSeconds(1));

    assertEquals(503, get("/ready").statusCode());
  }

  @Test
  void readiness_recovers_once_a_poll_succeeds_again() throws Exception {
    MovableClock clock = new MovableClock();
    CachingServiceDirectory directory = new CachingServiceDirectory(clock);
    directory.replaceAll(Map.of());
    server = new SkaldHealthServer(directory, 0, Duration.ofSeconds(30));
    clock.advance(Duration.ofSeconds(31));
    assertEquals(503, get("/ready").statusCode());

    directory.replaceAll(Map.of("api.acme.svc.gimle.local", List.of()));

    assertEquals(200, get("/ready").statusCode());
  }

  @Test
  void the_health_port_is_reported_so_an_ephemeral_bind_is_discoverable() throws IOException {
    server = new SkaldHealthServer(new CachingServiceDirectory(), 0, Duration.ofSeconds(30));

    assertTrue(server.port() > 0);
  }
}
