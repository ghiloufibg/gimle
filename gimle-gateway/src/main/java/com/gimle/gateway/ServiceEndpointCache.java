package com.gimle.gateway;

import com.gimle.core.protocol.Json;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleContext.RelayResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and round-robins over the live endpoints of a {@link GatewayRoute.ServiceRoute}'s target
 * control-plane {@code Service}, refreshed through {@code
 * ModuleContext#relayControlPlaneRead("/services/" + serviceName + "/endpoints")} on the same
 * TTL-cache posture {@link VesselEndpointCache} already established for the analogous vessel-route
 * lookup -- see that class's own javadoc for why polling on a TTL rather than per request is the
 * right tradeoff here too (a relay round trip crosses this instance's worker and agent on every
 * call).
 *
 * <p>Unlike {@link VesselEndpointCache}, a service's endpoint entries carry their own {@code host}/
 * {@code port} directly rather than a map of named ports -- a {@code Service} already fixes the one
 * port its endpoints are reachable on, so there is no separate {@code portName} to look up. An
 * endpoint-list entry counts as usable ("ready") only if it carries a non-blank {@code host} and a
 * positive {@code port}; a refresh that fails falls back to the still-cached list if one exists,
 * the same one-bad-refresh-doesn't-fail-every-in-flight-request posture {@link VesselEndpointCache}
 * already takes -- see {@link #resolve}.
 */
final class ServiceEndpointCache {

  private static final Logger log = LoggerFactory.getLogger(ServiceEndpointCache.class);

  /** No independent judgment call needed here -- the same TTL {@link VesselEndpointCache} uses. */
  static final Duration DEFAULT_TTL = VesselEndpointCache.DEFAULT_TTL;

  private final ModuleContext ctx;
  private final Duration ttl;
  private final Clock clock;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

  ServiceEndpointCache(ModuleContext ctx) {
    this(ctx, DEFAULT_TTL, Clock.systemUTC());
  }

  ServiceEndpointCache(ModuleContext ctx, Duration ttl, Clock clock) {
    this.ctx = ctx;
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * Picks the next ready endpoint (round-robin, same {@code AtomicInteger}-cursor tie-break {@link
   * VesselEndpointCache#resolve} already uses) for {@code serviceName}. Never throws: every
   * resolution failure this class knows about becomes an {@link Outcome.Unavailable} instead.
   */
  Outcome resolve(String serviceName) {
    List<Endpoint> endpoints;
    try {
      endpoints = endpointsFor(serviceName);
    } catch (GatewayUpstreamException e) {
      return new Outcome.Unavailable(e.status(), e.getMessage());
    }

    List<HostPort> ready = readyTargets(endpoints);
    if (ready.isEmpty()) {
      return new Outcome.Unavailable(
          503,
          "no ready endpoint for service '"
              + serviceName
              + "' ("
              + endpoints.size()
              + " endpoint(s) known, none expose a usable host/port)");
    }

    AtomicInteger cursor =
        roundRobinCursors.computeIfAbsent(serviceName, key -> new AtomicInteger());
    int index = Math.floorMod(cursor.getAndIncrement(), ready.size());
    return new Outcome.Ready(ready.get(index));
  }

  private List<Endpoint> endpointsFor(String serviceName) {
    CacheEntry cached = cache.get(serviceName);
    if (cached != null && !isStale(cached)) {
      return cached.endpoints();
    }

    String path = "/services/" + serviceName + "/endpoints";
    RelayResult result = ctx.relayControlPlaneRead(path);
    if (!isSuccess(result)) {
      return fallbackOrFail(
          serviceName, cached, "relay to " + path + " returned status " + result.status());
    }

    List<Endpoint> parsed;
    try {
      parsed = parseEndpoints(result.body());
    } catch (RuntimeException e) {
      return fallbackOrFail(
          serviceName,
          cached,
          "relay to " + path + " returned an unparsable body: " + e.getMessage());
    }

    cache.put(serviceName, new CacheEntry(parsed, clock.instant()));
    return parsed;
  }

  private List<Endpoint> fallbackOrFail(String serviceName, CacheEntry cached, String reason) {
    if (cached != null) {
      log.warn(
          "service endpoint refresh failed for service '{}' ({}); serving cached list fetched at"
              + " {}",
          serviceName,
          reason,
          cached.fetchedAt());
      return cached.endpoints();
    }
    throw new GatewayUpstreamException(
        502, "could not resolve endpoints for service '" + serviceName + "': " + reason);
  }

  private boolean isStale(CacheEntry entry) {
    return Duration.between(entry.fetchedAt(), clock.instant()).compareTo(ttl) >= 0;
  }

  private static boolean isSuccess(RelayResult result) {
    return result.status() >= 200 && result.status() < 300;
  }

  private static List<HostPort> readyTargets(List<Endpoint> endpoints) {
    List<HostPort> ready = new ArrayList<>();
    for (Endpoint endpoint : endpoints) {
      if (endpoint.host().isEmpty() || endpoint.port() <= 0) {
        continue;
      }
      ready.add(new HostPort(endpoint.host().get(), endpoint.port()));
    }
    return ready;
  }

  /**
   * Parses a {@code GET /services/{name}/endpoints} response body: a flat JSON object carrying the
   * service's own {@code name}/{@code port}/{@code targetPort} (unused here -- this class only
   * needs the endpoint list itself) plus an {@code endpoints} array of {@code {host, port}} pairs.
   * A missing/blank {@code host} or a non-positive {@code port} on one entry marks that entry
   * not-ready rather than failing the whole parse, mirroring {@link VesselEndpointCache}'s own
   * per-entry readiness filtering.
   */
  private static List<Endpoint> parseEndpoints(String body) {
    Map<String, Object> root = Json.asObject(Json.parse(body));
    Object rawEndpoints = root.get("endpoints");
    if (rawEndpoints == null) {
      return List.of();
    }
    List<Map<String, Object>> entries = Json.asObjectList(rawEndpoints);
    List<Endpoint> endpoints = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      endpoints.add(toEndpoint(entry));
    }
    return List.copyOf(endpoints);
  }

  private static Endpoint toEndpoint(Map<String, Object> entry) {
    Optional<String> host = Optional.ofNullable((String) entry.get("host"));
    Object rawPort = entry.get("port");
    int port = rawPort == null ? -1 : ((Number) rawPort).intValue();
    return new Endpoint(host, port);
  }

  /** One parsed {@code GET /services/{name}/endpoints} {@code endpoints} array entry. */
  private record Endpoint(Optional<String> host, int port) {}

  private record CacheEntry(List<Endpoint> endpoints, Instant fetchedAt) {}

  /** The result of one {@link #resolve} call. */
  sealed interface Outcome permits Outcome.Ready, Outcome.Unavailable {

    /** A ready target was found; proxy the request to it. */
    record Ready(HostPort target) implements Outcome {}

    /** No target could be found -- report {@code status}/{@code message} back to the caller. */
    record Unavailable(int status, String message) implements Outcome {}
  }
}
