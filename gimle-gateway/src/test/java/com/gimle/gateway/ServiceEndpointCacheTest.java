package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
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
 * Exercises {@link ServiceEndpointCache} directly against a hand-built {@link SimpleModuleContext}
 * whose relay is a plain {@link Function} -- the same "no real cluster needed" posture {@link
 * VesselEndpointCacheTest} already uses for its own analogous cache. {@link MutableClock} is a tiny
 * test double, copied rather than shared with {@link VesselEndpointCacheTest}'s own private nested
 * copy since it's not reflectively invoked and needs no wider visibility.
 */
class ServiceEndpointCacheTest {

  private static SimpleModuleContext contextWithRelay(Function<String, RelayResult> relay) {
    return new SimpleModuleContext(
        ModuleInstanceId.unattached(new ModuleId("com.gimle.gateway", Version.parse("1.0.0"))),
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
                  "{\"name\":\"payments\",\"port\":8080,\"targetPort\":8080,"
                      + "\"endpoints\":[{\"host\":\"10.0.0.5\",\"port\":54321}]}");
            });
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    ServiceEndpointCache.Outcome.Ready ready =
        assertInstanceOf(ServiceEndpointCache.Outcome.Ready.class, outcome);
    assertEquals("10.0.0.5", ready.target().host());
    assertEquals(54321, ready.target().port());
    assertEquals(1, relayCalls.get());
  }

  @Test
  void relays_to_the_services_endpoints_path_for_the_named_service() {
    AtomicInteger relayCalls = new AtomicInteger();
    SimpleModuleContext ctx =
        contextWithRelay(
            path -> {
              assertEquals("/services/payments/endpoints", path);
              relayCalls.incrementAndGet();
              return new RelayResult(200, "{\"name\":\"payments\",\"endpoints\":[]}");
            });
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    cache.resolve("payments");

    assertEquals(1, relayCalls.get());
  }

  @Test
  void skips_endpoints_missing_a_usable_host_or_port() {
    SimpleModuleContext ctx =
        contextWithRelay(
            path ->
                new RelayResult(
                    200,
                    "{\"name\":\"payments\",\"endpoints\":["
                        + "{\"port\":1},"
                        + "{\"host\":\"10.0.0.6\",\"port\":0},"
                        + "{\"host\":\"10.0.0.7\",\"port\":9999}"
                        + "]}"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    ServiceEndpointCache.Outcome.Ready ready =
        assertInstanceOf(ServiceEndpointCache.Outcome.Ready.class, outcome);
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
                    "{\"name\":\"payments\",\"endpoints\":["
                        + "{\"host\":\"10.0.0.5\",\"port\":1},"
                        + "{\"host\":\"10.0.0.6\",\"port\":2}"
                        + "]}"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    Set<Integer> seenPorts = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      ServiceEndpointCache.Outcome.Ready ready =
          assertInstanceOf(ServiceEndpointCache.Outcome.Ready.class, cache.resolve("payments"));
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
                  "{\"name\":\"payments\",\"endpoints\":[{\"host\":\"10.0.0.5\",\"port\":1}]}");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ServiceEndpointCache cache = new ServiceEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("payments");
    cache.resolve("payments");

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
                  "{\"name\":\"payments\",\"endpoints\":[{\"host\":\"10.0.0.5\",\"port\":1}]}");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ServiceEndpointCache cache = new ServiceEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("payments");
    clock.advance(Duration.ofSeconds(6));
    cache.resolve("payments");

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
                    "{\"name\":\"payments\",\"endpoints\":[{\"host\":\"10.0.0.5\",\"port\":1}]}");
              }
              return new RelayResult(504, "control plane unreachable");
            });
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    ServiceEndpointCache cache = new ServiceEndpointCache(ctx, Duration.ofSeconds(5), clock);

    cache.resolve("payments");
    clock.advance(Duration.ofSeconds(6));
    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    ServiceEndpointCache.Outcome.Ready ready =
        assertInstanceOf(ServiceEndpointCache.Outcome.Ready.class, outcome);
    assertEquals(1, ready.target().port());
    assertEquals(2, relayCalls.get());
  }

  @Test
  void a_terminal_relay_status_with_nothing_cached_yet_is_a_clear_error() {
    SimpleModuleContext ctx =
        contextWithRelay(path -> new RelayResult(403, "path not whitelisted"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    ServiceEndpointCache.Outcome.Unavailable unavailable =
        assertInstanceOf(ServiceEndpointCache.Outcome.Unavailable.class, outcome);
    assertTrue(unavailable.status() >= 400);
  }

  @Test
  void an_unparsable_relay_body_with_nothing_cached_yet_is_a_clear_error() {
    SimpleModuleContext ctx = contextWithRelay(path -> new RelayResult(200, "not json"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    assertInstanceOf(ServiceEndpointCache.Outcome.Unavailable.class, outcome);
  }

  @Test
  void an_empty_endpoint_list_is_a_clear_error_not_a_silent_200() {
    SimpleModuleContext ctx =
        contextWithRelay(path -> new RelayResult(200, "{\"name\":\"payments\",\"endpoints\":[]}"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    ServiceEndpointCache.Outcome.Unavailable unavailable =
        assertInstanceOf(ServiceEndpointCache.Outcome.Unavailable.class, outcome);
    assertEquals(503, unavailable.status());
  }

  @Test
  void a_response_with_no_endpoints_field_at_all_is_a_clear_error() {
    SimpleModuleContext ctx =
        contextWithRelay(path -> new RelayResult(200, "{\"name\":\"payments\"}"));
    ServiceEndpointCache cache =
        new ServiceEndpointCache(ctx, ServiceEndpointCache.DEFAULT_TTL, Clock.systemUTC());

    ServiceEndpointCache.Outcome outcome = cache.resolve("payments");

    assertInstanceOf(ServiceEndpointCache.Outcome.Unavailable.class, outcome);
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
