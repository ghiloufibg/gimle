package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.ParamType;
import com.gimle.module.lifecycle.ModuleContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP-request-to-fabric-call-to-HTTP-response core of the gateway, deliberately kept free of
 * {@code com.sun.net.httpserver} types so it's testable against a hand-built {@link ModuleContext}
 * without a real bound socket -- {@link GatewayHooks} is the thin adapter that actually wires an
 * {@code HttpServer} to this.
 *
 * <p>A {@link ParamType#NONE} route is served on {@code GET}; every other route is served on {@code
 * POST}, with the raw HTTP request body supplying the single argument's plain-text representation
 * (see {@link ParamType#coerce}). A route's real return value -- whatever {@code
 * ModuleContext#invokeServiceByName} hands back -- is serialized via {@link String#valueOf}, which
 * is exact for the only return shapes v1 supports ({@link String}, a boxed primitive, or {@code
 * void}/{@code null}).
 */
public final class GatewayDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GatewayDispatcher.class);

  private final ModuleContext ctx;
  private final Map<String, GatewayRoute> routesByPath;

  public GatewayDispatcher(ModuleContext ctx, List<GatewayRoute> routes) {
    this.ctx = ctx;
    Map<String, GatewayRoute> byPath = new LinkedHashMap<>();
    for (GatewayRoute route : routes) {
      byPath.put(route.path(), route);
    }
    this.routesByPath = Map.copyOf(byPath);
  }

  /**
   * Dispatches one inbound HTTP request. Never throws: every failure mode this method knows about
   * -- no such route, wrong HTTP verb, a body that doesn't coerce to the route's declared {@link
   * ParamType}, or the downstream fabric call itself failing -- becomes a {@link GatewayResponse}
   * with an appropriate status, so a caller (ultimately {@link GatewayHooks}) never has to catch an
   * exception to produce a real HTTP response.
   *
   * <p>A successful call whose real result is {@link Optional#empty()} is served as {@code 200}
   * with an empty body -- {@code ModuleContext#invokeServiceByName}'s own javadoc explains why an
   * empty result can mean either "no exporter known for this interface/version" or a genuine {@code
   * void}/{@code null} return, and this dispatcher has no separate signal to tell those two apart.
   * A misconfigured route naming a service nothing exports therefore reads as a quiet success
   * rather than a clear error in v1 -- a known, accepted limitation of routing purely by name with
   * no separate existence check.
   */
  public GatewayResponse dispatch(String httpMethod, String path, String body) {
    GatewayRoute route = routesByPath.get(path);
    if (route == null) {
      return new GatewayResponse(404, "no gateway route for " + path);
    }

    String expectedMethod = route.paramType() == ParamType.NONE ? "GET" : "POST";
    if (!expectedMethod.equals(httpMethod)) {
      return new GatewayResponse(405, "route " + path + " requires " + expectedMethod);
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
          path,
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

  /**
   * A finished HTTP response this dispatcher decided on -- {@code status} plus a plain-text {@code
   * body}, never JSON (matching the plain-text request-body convention {@link ParamType} uses).
   */
  public record GatewayResponse(int status, String body) {}
}
