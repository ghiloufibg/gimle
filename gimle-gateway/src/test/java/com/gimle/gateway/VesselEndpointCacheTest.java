package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.module.lifecycle.ModuleContext.RelayResult;
import com.gimle.module.lifecycle.SimpleModuleContext;
import com.gimle.module.lifecycle.SimpleServiceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link VesselEndpointCache} directly against a hand-built {@link SimpleModuleContext}
 * whose relay is a plain {@link Function} -- the same "no real cluster needed" posture {@link
 * GatewayDispatcherTest} already uses for fabric routes. {@link MutableClock} is a tiny test double
 * (not reflectively invoked, so it stays a private nested class rather than needing the top-level
 * visibility {@link TestGreeter}'s own javadoc explains) that lets a test advance time
 * deterministically to exercise TTL expiry without a real sleep.
 */
class VesselEndpointCacheTest {

  private static SimpleModuleContext contextWithRelay(Function<String, RelayResult> relay) {
    return new SimpleModuleContext(
        new ModuleId("com.gimle.gateway", Version.parse("1.0.0")),
        new SimpleServiceRegistry(),
        new ConcurrentHashMap<>(),
        Map.of(),
        relay);
  }

  @Test
  void resolves_a_ready_endpoint_to_its_host_and_port() {
    AtomicInteger relayCalls = new AtomicInteger();
    SimpleModuleContext ctx =
        contextWithRelay(
            path -> {
              relayCalls.incrementAndGet();
              return new RelayResult(
                  200,
                  "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                      + "\"ports\":{\"HTTP_PORT\":54321}}]");
            });
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    VesselEndpointCache.Outcome.Ready ready =
        assertInstanceOf(VesselEndpointCache.Outcome.Ready.class, outcome);
    assertEquals("10.0.0.5", ready.target().host());
    assertEquals(54321, ready.target().port());
    assertEquals(1, relayCalls.get());
  }

  @Test
  void skips_endpoints_missing_the_named_port_or_the_host() {
    SimpleModuleContext ctx =
        contextWithRelay(
            path ->
                new RelayResult(
                    200,
                    "["
                        + "{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"ports\":{\"HTTP_PORT\":1}},"
                        + "{\"instanceIndex\":1,\"nodeId\":\"node-b\",\"host\":\"10.0.0.6\","
                        + "\"ports\":{\"OTHER_PORT\":2}},"
                        + "{\"instanceIndex\":2,\"nodeId\":\"node-c\",\"host\":\"10.0.0.7\","
                        + "\"ports\":{\"HTTP_PORT\":9999}}"
                        + "]"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    VesselEndpointCache.Outcome.Ready ready =
        assertInstanceOf(VesselEndpointCache.Outcome.Ready.class, outcome);
    assertEquals("10.0.0.7", ready.target().host());
    assertEquals(9999, ready.target().port());
  }

  @Test
  void round_robins_across_every_ready_endpoint_over_repeated_calls() {
    SimpleModuleContext ctx =
        contextWithRelay(
            path ->
                new RelayResult(
                    200,
                    "["
                        + "{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                        + "\"ports\":{\"HTTP_PORT\":1}},"
                        + "{\"instanceIndex\":1,\"nodeId\":\"node-b\",\"host\":\"10.0.0.6\","
                        + "\"ports\":{\"HTTP_PORT\":2}}"
                        + "]"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    Set<Integer> seenPorts = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      VesselEndpointCache.Outcome.Ready ready =
          assertInstanceOf(
              VesselEndpointCache.Outcome.Ready.class,
              cache.resolve("orders-service", "HTTP_PORT"));
      seenPorts.add(ready.target().port());
    }

    assertEquals(Set.of(1, 2), seenPorts);
  }

  @Test
  void a_call_within_the_ttl_does_not_relay_again() {
    AtomicInteger relayCalls = new AtomicInteger();
    SimpleModuleContext ctx =
        contextWithRelay(
            path -> {
              relayCalls.incrementAndGet();
              return new RelayResult(
                  200,
                  "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                      + "\"ports\":{\"HTTP_PORT\":1}}]");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    VesselEndpointCache cache = new VesselEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("orders-service", "HTTP_PORT");
    cache.resolve("orders-service", "HTTP_PORT");

    assertEquals(1, relayCalls.get());
  }

  @Test
  void a_call_past_the_ttl_relays_again() {
    AtomicInteger relayCalls = new AtomicInteger();
    SimpleModuleContext ctx =
        contextWithRelay(
            path -> {
              relayCalls.incrementAndGet();
              return new RelayResult(
                  200,
                  "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                      + "\"ports\":{\"HTTP_PORT\":1}}]");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    VesselEndpointCache cache = new VesselEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("orders-service", "HTTP_PORT");
    clock.advance(Duration.ofSeconds(6));
    cache.resolve("orders-service", "HTTP_PORT");

    assertEquals(2, relayCalls.get());
  }

  @Test
  void a_non_2xx_refresh_falls_back_to_the_stale_cached_list() {
    AtomicInteger relayCalls = new AtomicInteger();
    SimpleModuleContext ctx =
        contextWithRelay(
            path -> {
              if (relayCalls.incrementAndGet() == 1) {
                return new RelayResult(
                    200,
                    "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                        + "\"ports\":{\"HTTP_PORT\":1}}]");
              }
              return new RelayResult(504, "agent unreachable");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    VesselEndpointCache cache = new VesselEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("orders-service", "HTTP_PORT");
    clock.advance(Duration.ofSeconds(6));
    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    VesselEndpointCache.Outcome.Ready ready =
        assertInstanceOf(VesselEndpointCache.Outcome.Ready.class, outcome);
    assertEquals(1, ready.target().port());
    assertEquals(2, relayCalls.get());
  }

  @Test
  void a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error() {
    // 403 is what the agent's own path whitelist would return for a rejected path -- shouldn't
    // happen for a well-formed /endpoints/{name} path, but this class must not fabricate a ready
    // target from nothing if it somehow does.
    SimpleModuleContext ctx =
        contextWithRelay(path -> new RelayResult(403, "path not whitelisted"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    VesselEndpointCache.Outcome.Unavailable unavailable =
        assertInstanceOf(VesselEndpointCache.Outcome.Unavailable.class, outcome);
    assertTrue(unavailable.status() >= 400);
  }

  @Test
  void an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error() {
    SimpleModuleContext ctx = contextWithRelay(path -> new RelayResult(200, "not json"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    assertInstanceOf(VesselEndpointCache.Outcome.Unavailable.class, outcome);
  }

  @Test
  void an_empty_endpoint_list_is_a_clear_error_not_a_silent_200() {
    SimpleModuleContext ctx = contextWithRelay(path -> new RelayResult(200, "[]"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    VesselEndpointCache.Outcome.Unavailable unavailable =
        assertInstanceOf(VesselEndpointCache.Outcome.Unavailable.class, outcome);
    assertEquals(503, unavailable.status());
  }

  @Test
  void every_endpoint_missing_the_named_port_is_a_clear_error() {
    SimpleModuleContext ctx =
        contextWithRelay(
            path ->
                new RelayResult(
                    200,
                    "[{\"instanceIndex\":0,\"nodeId\":\"node-a\",\"host\":\"10.0.0.5\","
                        + "\"ports\":{\"OTHER_PORT\":1}}]"));
    VesselEndpointCache cache =
        new VesselEndpointCache(ctx, VesselEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    VesselEndpointCache.Outcome outcome = cache.resolve("orders-service", "HTTP_PORT");

    assertInstanceOf(VesselEndpointCache.Outcome.Unavailable.class, outcome);
  }

  /** A {@link Clock} a test can advance by hand, to exercise TTL expiry without a real sleep. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration amount) {
      now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
