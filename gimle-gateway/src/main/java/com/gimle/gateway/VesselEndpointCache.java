package com.gimle.gateway;

import com.gimle.core.protocol.Json;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleContext.RelayResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and round-robins over the live endpoints of a {@link GatewayRoute.VesselRoute}'s target
 * deployment, refreshed through {@code ModuleContext#relayControlPlaneRead("/endpoints/" +
 * deploymentName)} once the cached list is older than {@link #ttl} rather than on every request --
 * a relay round trip crosses this instance's worker and agent on every call, so paying it per
 * inbound HTTP request would make the gateway itself the latency bottleneck it exists not to be.
 *
 * <p>An endpoint-list entry counts as usable ("ready") only if it carries both a {@code host} and
 * the specific named port a route asks for -- {@code GET /endpoints/{name}} carries no explicit
 * ready/not-ready flag today, so this is a deliberately simple stand-in for real health, not an
 * attempt at one. A refresh that fails (a non-2xx {@link RelayResult}, or a body that doesn't parse
 * as the expected shape) falls back to the still-cached list if one exists, so one bad refresh
 * doesn't fail every in-flight request; only a failure with nothing cached at all (including the
 * very first resolution ever attempted for a deployment) surfaces as an error to the caller -- see
 * {@link #resolve}.
 */
final class VesselEndpointCache {

  private static final Logger log = LoggerFactory.getLogger(VesselEndpointCache.class);

  /**
   * How long a cached endpoint list is trusted before the next {@link #resolve} call pays for a
   * fresh relay round trip. No prior TTL-cache precedent exists anywhere else in this codebase to
   * match -- this value is this class's own judgment call: long enough that a steady request stream
   * to one vessel route doesn't relay on every single call, short enough that a rescheduled or
   * rescaled deployment's new endpoint list shows up within a handful of requests rather than
   * needing the gateway instance restarted.
   */
  static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

  private final ModuleContext ctx;
  private final Duration ttl;
  private final Clock clock;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

  VesselEndpointCache(ModuleContext ctx) {
    this(ctx, DEFAULT_TTL, Clock.systemUTC());
  }

  VesselEndpointCache(ModuleContext ctx, Duration ttl, Clock clock) {
    this.ctx = ctx;
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * Picks the next ready endpoint (round-robin, tie-broken the same {@code AtomicInteger}-cursor
   * way {@code LeastOutstandingRequestsSelector} in {@code gimle-fabric} breaks its own ties --
   * this class can't depend on that module, so this is a small, independent reimplementation of
   * just that one idiom) for {@code deploymentName}'s {@code portName}. Never throws: every
   * resolution failure this class knows about becomes an {@link Outcome.Unavailable} instead.
   */
  Outcome resolve(String deploymentName, String portName) {
    List<Endpoint> endpoints;
    try {
      endpoints = endpointsFor(deploymentName);
    } catch (GatewayUpstreamException e) {
      return new Outcome.Unavailable(e.status(), e.getMessage());
    }

    List<HostPort> ready = readyTargets(endpoints, portName);
    if (ready.isEmpty()) {
      return new Outcome.Unavailable(
          503,
          "no ready endpoint for deployment '"
              + deploymentName
              + "' port '"
              + portName
              + "' ("
              + endpoints.size()
              + " endpoint(s) known, none expose that port with a host)");
    }

    AtomicInteger cursor =
        roundRobinCursors.computeIfAbsent(deploymentName, key -> new AtomicInteger());
    int index = Math.floorMod(cursor.getAndIncrement(), ready.size());
    return new Outcome.Ready(ready.get(index));
  }

  private List<Endpoint> endpointsFor(String deploymentName) {
    CacheEntry cached = cache.get(deploymentName);
    if (cached != null && !isStale(cached)) {
      return cached.endpoints();
    }

    RelayResult result = ctx.relayControlPlaneRead("/endpoints/" + deploymentName);
    if (!isSuccess(result)) {
      return fallbackOrFail(
          deploymentName,
          cached,
          "relay to /endpoints/" + deploymentName + " returned status " + result.status());
    }

    List<Endpoint> parsed;
    try {
      parsed = parseEndpoints(result.body());
    } catch (RuntimeException e) {
      return fallbackOrFail(
          deploymentName,
          cached,
          "relay to /endpoints/"
              + deploymentName
              + " returned an unparsable body: "
              + e.getMessage());
    }

    cache.put(deploymentName, new CacheEntry(parsed, clock.instant()));
    return parsed;
  }

  private List<Endpoint> fallbackOrFail(String deploymentName, CacheEntry cached, String reason) {
    if (cached != null) {
      log.warn(
          "vessel endpoint refresh failed for deployment '{}' ({}); serving cached list fetched at"
              + " {}",
          deploymentName,
          reason,
          cached.fetchedAt());
      return cached.endpoints();
    }
    throw new GatewayUpstreamException(
        502, "could not resolve endpoints for deployment '" + deploymentName + "': " + reason);
  }

  private boolean isStale(CacheEntry entry) {
    return Duration.between(entry.fetchedAt(), clock.instant()).compareTo(ttl) >= 0;
  }

  private static boolean isSuccess(RelayResult result) {
    return result.status() >= 200 && result.status() < 300;
  }

  private static List<HostPort> readyTargets(List<Endpoint> endpoints, String portName) {
    List<HostPort> ready = new ArrayList<>();
    for (Endpoint endpoint : endpoints) {
      if (endpoint.host().isEmpty()) {
        continue;
      }
      Integer port = endpoint.ports().get(portName);
      if (port == null) {
        continue;
      }
      ready.add(new HostPort(endpoint.host().get(), port));
    }
    return ready;
  }

  /**
   * Parses a {@code GET /endpoints/{name}} response body: a JSON array of flat objects, each with
   * an integer {@code instanceIndex}, a string {@code nodeId}, an optional string {@code host}, and
   * an optional {@code ports} object mapping port name to integer port number -- {@code host}
   * and/or {@code ports} are each present only once the agent has actually reported them, never
   * present-but-null (see {@link GatewayRoute.VesselRoute}'s own javadoc pointer to the API this
   * mirrors).
   */
  private static List<Endpoint> parseEndpoints(String body) {
    List<Map<String, Object>> entries = Json.asObjectList(Json.parse(body));
    List<Endpoint> endpoints = new ArrayList<>();
    for (Map<String, Object> entry : entries) {
      endpoints.add(toEndpoint(entry));
    }
    return List.copyOf(endpoints);
  }

  private static Endpoint toEndpoint(Map<String, Object> entry) {
    int instanceIndex = ((Number) entry.get("instanceIndex")).intValue();
    String nodeId = (String) entry.get("nodeId");
    Optional<String> host = Optional.ofNullable((String) entry.get("host"));
    Map<String, Integer> ports = new LinkedHashMap<>();
    Object rawPorts = entry.get("ports");
    if (rawPorts != null) {
      for (Map.Entry<String, Object> portEntry : Json.asObject(rawPorts).entrySet()) {
        ports.put(portEntry.getKey(), ((Number) portEntry.getValue()).intValue());
      }
    }
    return new Endpoint(instanceIndex, nodeId, host, Map.copyOf(ports));
  }

  /** One parsed {@code GET /endpoints/{name}} array entry. */
  private record Endpoint(
      int instanceIndex, String nodeId, Optional<String> host, Map<String, Integer> ports) {}

  private record CacheEntry(List<Endpoint> endpoints, Instant fetchedAt) {}

  /** A resolved, dialable target for a vessel proxy call. */
  record HostPort(String host, int port) {}

  /** The result of one {@link #resolve} call. */
  sealed interface Outcome permits Outcome.Ready, Outcome.Unavailable {

    /** A ready target was found; proxy the request to it. */
    record Ready(HostPort target) implements Outcome {}

    /** No target could be found -- report {@code status}/{@code message} back to the caller. */
    record Unavailable(int status, String message) implements Outcome {}
  }
}
