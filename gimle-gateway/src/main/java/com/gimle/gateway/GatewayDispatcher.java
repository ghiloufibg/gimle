package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.FabricRoute;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import com.gimle.gateway.GatewayRoute.ServiceRoute;
import com.gimle.gateway.GatewayRoute.VesselRoute;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.ArrayList;
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
 * HttpServer} to this. Dispatch is exact-path lookup only ({@link Map#get(Object)} against a route
 * table keyed by literal path) -- no prefix/wildcard routing for any route kind, a deliberate v1
 * restriction rather than an oversight: it's what lets a {@link VesselRoute}/{@link ServiceRoute}
 * forward its inbound path to its target verbatim (see those records' own javadoc) with no
 * rewriting logic to get wrong. Host matching (see {@link #selectRoute}) is exact-value too, the
 * same posture applied to a second dimension rather than a new kind of matching.
 *
 * <p>A {@link FabricRoute} is served exactly as before this module gained additional route kinds: a
 * {@link ParamType#NONE} route on {@code GET}, every other route on {@code POST} with the request
 * body supplying its single argument (see {@link ParamType#coerce}), and the real return value --
 * whatever {@code ModuleContext#invokeServiceByName} hands back -- serialized via {@link
 * String#valueOf}. A {@link VesselRoute} is served by resolving a live target through {@link
 * VesselEndpointCache} and proxying the request to it via {@link VesselProxyClient}; a {@link
 * ServiceRoute} is served the identical way but through {@link ServiceEndpointCache} instead. Both
 * are unrestricted on HTTP method and request body (see {@link VesselRoute}'s own javadoc for
 * exactly what "unrestricted" excludes -- request/response headers are not forwarded in v1).
 */
public final class GatewayDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GatewayDispatcher.class);

  private final ModuleContext ctx;
  private final Map<String, List<GatewayRoute>> routesByPath;
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
    Map<String, List<GatewayRoute>> byPath = new LinkedHashMap<>();
    for (GatewayRoute route : routes) {
      byPath.computeIfAbsent(route.path(), key -> new ArrayList<>()).add(route);
    }
    Map<String, List<GatewayRoute>> immutableByPath = new LinkedHashMap<>();
    for (Map.Entry<String, List<GatewayRoute>> entry : byPath.entrySet()) {
      immutableByPath.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    this.routesByPath = Map.copyOf(immutableByPath);
    this.vesselEndpointCache = vesselEndpointCache;
    this.serviceEndpointCache = serviceEndpointCache;
    this.vesselProxyClient = vesselProxyClient;
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
    List<GatewayRoute> candidates = routesByPath.get(path);
    if (candidates == null) {
      return new GatewayResponse(404, "no gateway route for " + path);
    }
    GatewayRoute route = selectRoute(candidates, hostHeader);
    if (route == null) {
      return new GatewayResponse(
          404, "no gateway route for " + path + " matching host '" + hostHeader + "'");
    }
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
