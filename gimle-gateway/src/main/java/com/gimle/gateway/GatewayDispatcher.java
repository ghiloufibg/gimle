package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.FabricRoute;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import com.gimle.gateway.GatewayRoute.ServiceRoute;
import com.gimle.gateway.GatewayRoute.VesselRoute;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP-request-to-response core of the gateway, deliberately kept free of {@code
 * com.sun.net.httpserver} types so it's testable against a hand-built {@link ModuleContext} without
 * a real bound socket -- {@link GatewayHooks} is the thin adapter that actually wires an {@code
 * HttpServer} to this.
 *
 * <p>Dispatch has two tiers, tried in order: an exact-literal-path lookup ({@link Map#get(Object)}
 * against {@link #exactRoutesByPath}) exactly as this module has always done it, then, only if that
 * misses, a prefix-match scan of {@link #prefixBuckets} -- one bucket per declared prefix (see
 * {@link GatewayRoute#prefix()}), pre-sorted longest-prefix-first at construction time so the most
 * specific matching prefix is always tried before a shorter, less specific one. This makes exact
 * match strictly the most specific possible match (it never loses to a prefix, even one whose
 * declared string is identical) and gives ordinary longest-prefix-match semantics among prefixes
 * themselves -- the standard behavior for this kind of routing table (same idea as an nginx {@code
 * location} block or a Kubernetes Ingress rule set), not "first registered wins." {@link
 * #matchesPrefix} is segment-boundary aware ({@code /api/orders} matches {@code /api/orders/42} but
 * not {@code /api/orders2}), and a bucket that matches the path but has no route willing to serve
 * the request's host (see {@link #selectRoute}) falls through to the next, shorter matching prefix
 * rather than 404ing outright -- the same "host-unconstrained sibling can still serve as a default"
 * behavior the exact-path tier has always had, now available at every matching specificity level
 * rather than just one. A handful to a few hundred declared routes -- this module's realistic scale
 * as a {@code DaemonSet}-deployed edge gateway, not a CDN -- is well within what a plain sorted
 * list scanned linearly handles fine; a trie or interval tree would be solving a problem this
 * deployment shape doesn't have.
 *
 * <p>Only {@link VesselRoute} and {@link ServiceRoute} can declare a prefix; a {@link FabricRoute}
 * is permanently exact-path-only (see {@link FabricRoute#prefix()} for why) and is never placed in
 * {@link #prefixBuckets}. Host matching (see {@link #selectRoute}) is exact-value, unaffected by
 * which path tier produced the candidate list it's applied to -- a route can be host-constrained
 * and prefix-matched at the same time, two independent dimensions.
 *
 * <p>A {@link FabricRoute} is served exactly as before this module gained additional route kinds: a
 * {@link ParamType#NONE} route on {@code GET}, every other route on {@code POST} with the request
 * body supplying its single argument (see {@link ParamType#coerce}), and the real return value --
 * whatever {@code ModuleContext#invokeServiceByName} hands back -- serialized via {@link
 * String#valueOf}. A {@link VesselRoute} is served by resolving a live target through {@link
 * VesselEndpointCache} and proxying the request to it via {@link VesselProxyClient}; a {@link
 * ServiceRoute} is served the identical way but through {@link ServiceEndpointCache} instead. Both
 * are unrestricted on HTTP method and request body, and -- exact-path or prefix-matched alike --
 * always proxy the request's own full, untouched inbound path onward, never a rewritten/stripped
 * one (see {@link VesselRoute}'s own javadoc for why that's the deliberate choice here, and for
 * what "unrestricted" excludes -- request/response headers are not forwarded in v1).
 */
public final class GatewayDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GatewayDispatcher.class);

  private final ModuleContext ctx;
  private final Map<String, List<GatewayRoute>> exactRoutesByPath;
  private final List<PrefixBucket> prefixBuckets;
  private final VesselEndpointCache vesselEndpointCache;
  private final ServiceEndpointCache serviceEndpointCache;
  private final VesselProxyClient vesselProxyClient;

  public GatewayDispatcher(ModuleContext ctx, List<GatewayRoute> routes) {
    this(
        ctx,
        routes,
        new VesselEndpointCache(ctx),
        new ServiceEndpointCache(ctx),
        new VesselProxyClient());
  }

  /**
   * Test-only seam: lets a test hand this dispatcher a {@link VesselEndpointCache} built with a
   * controlled TTL/clock (to exercise staleness/refresh deterministically) and/or its own {@link
   * VesselProxyClient}, without the public single-arg-list constructor above needing to expose
   * either. Predates {@link ServiceEndpointCache} -- kept so every pre-existing call site continues
   * to compile, defaulting the service cache to an ordinary real one.
   */
  GatewayDispatcher(
      ModuleContext ctx,
      List<GatewayRoute> routes,
      VesselEndpointCache vesselEndpointCache,
      VesselProxyClient vesselProxyClient) {
    this(ctx, routes, vesselEndpointCache, new ServiceEndpointCache(ctx), vesselProxyClient);
  }

  /**
   * Test-only seam: the full-control version of the constructor above, additionally letting a test
   * hand this dispatcher its own {@link ServiceEndpointCache} (controlled TTL/clock) for {@link
   * ServiceRoute} coverage.
   */
  GatewayDispatcher(
      ModuleContext ctx,
      List<GatewayRoute> routes,
      VesselEndpointCache vesselEndpointCache,
      ServiceEndpointCache serviceEndpointCache,
      VesselProxyClient vesselProxyClient) {
    this.ctx = ctx;
    Map<String, List<GatewayRoute>> exactByPath = new LinkedHashMap<>();
    Map<String, List<GatewayRoute>> prefixByPath = new LinkedHashMap<>();
    for (GatewayRoute route : routes) {
      Map<String, List<GatewayRoute>> target = route.prefix() ? prefixByPath : exactByPath;
      target.computeIfAbsent(route.path(), key -> new ArrayList<>()).add(route);
    }
    this.exactRoutesByPath = immutableGroupedByPath(exactByPath);
    List<PrefixBucket> buckets = new ArrayList<>();
    for (Map.Entry<String, List<GatewayRoute>> entry : prefixByPath.entrySet()) {
      buckets.add(new PrefixBucket(entry.getKey(), List.copyOf(entry.getValue())));
    }
    // Longest prefix first, so dispatch always tries the most specific matching prefix before a
    // shorter one -- see this class's own javadoc for why that's the right precedence rule here.
    buckets.sort(
        Comparator.comparingInt((PrefixBucket bucket) -> bucket.prefix().length()).reversed());
    this.prefixBuckets = List.copyOf(buckets);
    this.vesselEndpointCache = vesselEndpointCache;
    this.serviceEndpointCache = serviceEndpointCache;
    this.vesselProxyClient = vesselProxyClient;
  }

  private static Map<String, List<GatewayRoute>> immutableGroupedByPath(
      Map<String, List<GatewayRoute>> grouped) {
    Map<String, List<GatewayRoute>> immutable = new LinkedHashMap<>();
    for (Map.Entry<String, List<GatewayRoute>> entry : grouped.entrySet()) {
      immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(immutable);
  }

  /**
   * One declared path prefix and the (possibly host-differentiated) routes registered at it -- the
   * prefix-match analogue of one key/value pair in {@link #exactRoutesByPath}, kept as its own
   * record rather than another map entry since {@link #prefixBuckets} needs a stable sort order
   * ({@link #dispatch} depends on longest-first) that a {@code Map} doesn't offer.
   */
  private record PrefixBucket(String prefix, List<GatewayRoute> routes) {}

  /**
   * Whether {@code path} is matched by the path prefix {@code prefix} -- segment-boundary aware, so
   * {@code /api/orders} matches {@code /api/orders} and {@code /api/orders/42} but not {@code
   * /api/orders2} (a naive {@link String#startsWith} would wrongly match the latter). The root
   * prefix {@code "/"} is a special-cased catch-all: every valid Gimlé gateway path already starts
   * with {@code "/"} (see every route record's own compact constructor), so it matches everything.
   */
  private static boolean matchesPrefix(String prefix, String path) {
    if (prefix.equals("/")) {
      return true;
    }
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /**
   * Dispatches one inbound HTTP request with no {@code Host} header to consider -- equivalent to
   * calling {@link #dispatch(String, String, String, String)} with a {@code null} host, so only a
   * host-unconstrained route (or a path with none declared at all) can ever answer. Kept so every
   * pre-existing call site continues to compile and behave identically.
   */
  public GatewayResponse dispatch(String httpMethod, String path, String body) {
    return dispatch(httpMethod, path, body, null);
  }

  /**
   * Dispatches one inbound HTTP request. Never throws: every failure mode this method knows about
   * -- no such route, wrong HTTP verb, a body that doesn't coerce to a fabric route's declared
   * {@link ParamType}, a downstream fabric call failing, or a vessel/service route with no
   * resolvable/ready target -- becomes a {@link GatewayResponse} with an appropriate status, so a
   * caller (ultimately {@link GatewayHooks}) never has to catch an exception to produce a real HTTP
   * response.
   */
  public GatewayResponse dispatch(String httpMethod, String path, String body, String hostHeader) {
    List<GatewayRoute> exactCandidates = exactRoutesByPath.get(path);
    if (exactCandidates != null) {
      GatewayRoute route = selectRoute(exactCandidates, hostHeader);
      if (route == null) {
        return new GatewayResponse(
            404, "no gateway route for " + path + " matching host '" + hostHeader + "'");
      }
      return serve(route, httpMethod, path, body);
    }

    boolean anyPrefixMatchedPath = false;
    for (PrefixBucket bucket : prefixBuckets) {
      if (!matchesPrefix(bucket.prefix(), path)) {
        continue;
      }
      anyPrefixMatchedPath = true;
      GatewayRoute route = selectRoute(bucket.routes(), hostHeader);
      if (route != null) {
        return serve(route, httpMethod, path, body);
      }
      // This prefix matched the path but none of its routes matched the request's host -- fall
      // through to the next, shorter matching prefix rather than 404ing outright, the same
      // "host-unconstrained sibling can still serve as a default" fallback the exact-path tier
      // has always offered within one bucket, now honored across specificity levels too.
    }
    return anyPrefixMatchedPath
        ? new GatewayResponse(
            404, "no gateway route for " + path + " matching host '" + hostHeader + "'")
        : new GatewayResponse(404, "no gateway route for " + path);
  }

  private GatewayResponse serve(GatewayRoute route, String httpMethod, String path, String body) {
    return switch (route) {
      case FabricRoute fabricRoute -> dispatchFabric(fabricRoute, httpMethod, body);
      case VesselRoute vesselRoute -> dispatchVessel(vesselRoute, httpMethod, path, body);
      case ServiceRoute serviceRoute -> dispatchService(serviceRoute, httpMethod, path, body);
    };
  }

  /**
   * Picks which of this path's candidate routes serves the request: an exact (case-insensitive)
   * {@code Host} match wins outright; failing that, a route declaring no host constraint (matches
   * any host -- the original behavior of every route kind) is used as the fallback; failing that --
   * every candidate at this path demands a specific host and none matched -- there is no route to
   * serve this request. This is what lets a host-unconstrained route keep answering every request
   * exactly as it did before host-based routing existed, even on a path that also carries
   * host-constrained siblings.
   */
  private static GatewayRoute selectRoute(List<GatewayRoute> candidates, String hostHeader) {
    GatewayRoute wildcard = null;
    for (GatewayRoute candidate : candidates) {
      Optional<String> requiredHost = candidate.host();
      if (requiredHost.isEmpty()) {
        wildcard = candidate;
        continue;
      }
      if (hostHeader != null && requiredHost.get().equalsIgnoreCase(hostHeader)) {
        return candidate;
      }
    }
    return wildcard;
  }

  /**
   * A successful call whose real result is {@link Optional#empty()} is served as {@code 200} with
   * an empty body -- {@code ModuleContext#invokeServiceByName}'s own javadoc explains why an empty
   * result can mean either "no exporter known for this interface/version" or a genuine {@code
   * void}/{@code null} return, and this dispatcher has no separate signal to tell those two apart.
   * A misconfigured route naming a service nothing exports therefore reads as a quiet success
   * rather than a clear error in v1 -- a known, accepted limitation of routing purely by name with
   * no separate existence check.
   */
  private GatewayResponse dispatchFabric(FabricRoute route, String httpMethod, String body) {
    String expectedMethod = route.paramType() == ParamType.NONE ? "GET" : "POST";
    if (!expectedMethod.equals(httpMethod)) {
      return new GatewayResponse(405, "route " + route.path() + " requires " + expectedMethod);
    }

    Object[] args;
    String[] paramTypeNames;
    if (route.paramType() == ParamType.NONE) {
      args = new Object[0];
      paramTypeNames = new String[0];
    } else {
      Object coerced;
      try {
        coerced = route.paramType().coerce(body == null ? "" : body);
      } catch (GatewayBadRequestException e) {
        return new GatewayResponse(400, e.getMessage());
      }
      args = new Object[] {coerced};
      paramTypeNames = new String[] {route.paramType().wireTypeName()};
    }

    Optional<Object> result;
    try {
      result =
          ctx.invokeServiceByName(
              route.interfaceName(),
              route.majorVersion(),
              route.methodName(),
              paramTypeNames,
              args);
    } catch (Throwable t) {
      log.warn(
          "gateway route {} failed calling {}#{}: {}",
          route.path(),
          route.interfaceName(),
          route.methodName(),
          t.toString());
      return new GatewayResponse(
          502,
          "call to "
              + route.interfaceName()
              + "#"
              + route.methodName()
              + " failed: "
              + t.getMessage());
    }

    return result
        .map(value -> new GatewayResponse(200, String.valueOf(value)))
        .orElse(new GatewayResponse(200, ""));
  }

  private GatewayResponse dispatchVessel(
      VesselRoute route, String httpMethod, String path, String body) {
    VesselEndpointCache.Outcome outcome =
        vesselEndpointCache.resolve(route.deploymentName(), route.portName());
    return switch (outcome) {
      case VesselEndpointCache.Outcome.Unavailable unavailable ->
          new GatewayResponse(unavailable.status(), unavailable.message());
      case VesselEndpointCache.Outcome.Ready ready ->
          vesselProxyClient.proxy(
              ready.target().host(), ready.target().port(), httpMethod, path, body);
    };
  }

  private GatewayResponse dispatchService(
      ServiceRoute route, String httpMethod, String path, String body) {
    ServiceEndpointCache.Outcome outcome = serviceEndpointCache.resolve(route.serviceName());
    return switch (outcome) {
      case ServiceEndpointCache.Outcome.Unavailable unavailable ->
          new GatewayResponse(unavailable.status(), unavailable.message());
      case ServiceEndpointCache.Outcome.Ready ready ->
          vesselProxyClient.proxy(
              ready.target().host(), ready.target().port(), httpMethod, path, body);
    };
  }

  /**
   * A finished HTTP response this dispatcher decided on -- {@code status} plus a body. A fabric
   * route's body is always plain text (matching {@link ParamType}'s own plain-text request-body
   * convention); a vessel or service route's body is whatever its target returned, unexamined and
   * passed through as-is.
   */
  public record GatewayResponse(int status, String body) {}
}
