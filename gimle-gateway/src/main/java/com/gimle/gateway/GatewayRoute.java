package com.gimle.gateway;

import java.util.Optional;
import java.util.function.Function;

/**
 * One declared gateway route: an HTTP {@code path} that, when hit, is served one of three ways.
 * {@link FabricRoute} invokes a named fabric service via {@code ModuleContext#invokeServiceByName}
 * -- the only kind this module originally supported. {@link VesselRoute} proxies the inbound
 * request to a live instance of a named deployment, resolved through {@code
 * ModuleContext#relayControlPlaneRead} (see {@link VesselEndpointCache}). {@link ServiceRoute}
 * proxies the inbound request the same way, but resolves its target through a control-plane-
 * declared {@code Service}'s own endpoint set instead of a named deployment port (see {@link
 * ServiceEndpointCache}). The three kinds share nothing beyond an HTTP path (an optional host
 * constraint, and -- for {@link VesselRoute}/{@link ServiceRoute} only, see {@link #prefix()} -- an
 * optional prefix-vs-exact match mode) to dispatch on, which is why this is a sealed interface of
 * record shapes rather than one flat record trying to cover all three -- see {@link
 * GatewayDispatcher#dispatch} for how each kind is actually served.
 */
public sealed interface GatewayRoute
    permits GatewayRoute.FabricRoute, GatewayRoute.VesselRoute, GatewayRoute.ServiceRoute {

  /**
   * The HTTP path this route answers. When {@link #prefix()} is {@code false} (the default), an
   * exact literal path -- unchanged behavior from before this route kind gained prefix matching.
   * When {@link #prefix()} is {@code true}, the prefix itself: normalized with no trailing slash
   * (or exactly {@code "/"} for a catch-all matching every path), matching itself and everything
   * path-segment-nested under it. See {@link GatewayDispatcher} for matching/precedence semantics.
   */
  String path();

  /**
   * Whether {@link #path()} names a path prefix -- matching itself and everything nested under it
   * -- rather than one exact literal path. {@code false} by default for every route kind, and
   * permanently {@code false} for a {@link FabricRoute} (see its own override for why). Declared in
   * the manifest-facing config via a trailing {@code /*} on the path field (see {@link
   * GatewayRouteConfig}), the same spelling Kubernetes Ingress/nginx use for a prefix path.
   */
  default boolean prefix() {
    return false;
  }

  /**
   * The {@code Host} header value this route requires, or {@link Optional#empty()} if it matches
   * any host -- the original, and still default, behavior of every route kind (a route declared
   * without an explicit host constraint keeps working exactly as it did before host-based routing
   * existed). See {@link GatewayDispatcher} for the matching precedence between a host-constrained
   * route and a host-unconstrained one sharing the same path.
   */
  Optional<String> host();

  /**
   * An HTTP-to-fabric route: an HTTP {@code path} that, when hit, invokes {@code methodName} on
   * whatever currently exports {@code interfaceName} at {@code majorVersion} via {@code
   * ModuleContext#invokeServiceByName}.
   *
   * <p>{@code paramType} names the single, simple-typed argument this route's target method takes,
   * or {@link ParamType#NONE} for a zero-argument method -- this is the v1 restriction stated on
   * {@link ParamType}'s own javadoc, not a general parameter list. There is no separate declared
   * return type: a route's target method may return {@code void} or one simple type (a {@link
   * String} or boxed primitive), and {@link GatewayDispatcher} serializes whatever comes back at
   * invocation time rather than needing it declared up front.
   */
  record FabricRoute(
      Optional<String> host,
      String path,
      String interfaceName,
      int majorVersion,
      String methodName,
      ParamType paramType)
      implements GatewayRoute {

    /**
     * Convenience constructor for a route with no host constraint -- the only shape this route kind
     * supported before host-based routing existed, kept so every pre-existing call site continues
     * to compile and behave identically.
     */
    public FabricRoute(
        String path,
        String interfaceName,
        int majorVersion,
        String methodName,
        ParamType paramType) {
      this(Optional.empty(), path, interfaceName, majorVersion, methodName, paramType);
    }

    public FabricRoute {
      if (host == null) {
        throw new GatewayConfigException(
            "route host must not be null (use Optional.empty() for no constraint)");
      }
      if (path == null || !path.startsWith("/")) {
        throw new GatewayConfigException("route path must start with '/': " + path);
      }
      if (interfaceName == null || interfaceName.isBlank()) {
        throw new GatewayConfigException("route interfaceName must not be blank");
      }
      if (methodName == null || methodName.isBlank()) {
        throw new GatewayConfigException("route methodName must not be blank");
      }
      if (paramType == null) {
        throw new GatewayConfigException("route paramType must not be null");
      }
    }

    /**
     * Deliberately, permanently exact-path-only -- prefix matching is not offered for this route
     * kind at all (there is no {@code /*}-suffixed spelling {@link GatewayRouteConfig} accepts for
     * a {@code FABRIC} line; it is rejected at parse time). A {@code FABRIC} route names one
     * specific fabric method call taking at most one simple-typed argument (see {@link ParamType}),
     * not a resource subtree: {@link GatewayDispatcher#dispatchFabric} never even reads the inbound
     * request path beyond the one this route is registered under, so a path segment past a would-be
     * prefix would carry no meaning and be silently discarded -- inviting a confusing "why did that
     * URL work" bug rather than serving any real use case the way a prefix genuinely does for a
     * {@link VesselRoute}/{@link ServiceRoute}, which proxy the full inbound path onward.
     */
    @Override
    public boolean prefix() {
      return false;
    }

    /**
     * The v1 argument-shape restriction, deliberate and stated up front rather than discovered by a
     * caller at dispatch time: a gateway route's target method takes zero arguments ({@link #NONE})
     * or exactly one, and that one argument must be a plain {@link String} or a boxed primitive --
     * never a general JSON-to-POJO mapping. {@link #NONE} routes are served on {@code GET}; every
     * other {@link ParamType} is served on {@code POST}, with the HTTP request body supplying the
     * argument's plain-text representation (not JSON-encoded -- a bare {@code 42}, not {@code "42"}
     * or {@code {"value":42}}), coerced via this constant's own {@link #coerce(String)}.
     */
    public enum ParamType {
      NONE {
        @Override
        Object coerce(String raw) {
          throw new UnsupportedOperationException("NONE takes no argument to coerce");
        }
      },
      STRING {
        @Override
        Object coerce(String raw) {
          return raw;
        }
      },
      INT {
        @Override
        Object coerce(String raw) {
          return parsedOrBadRequest(raw, Integer::parseInt, "an integer");
        }
      },
      LONG {
        @Override
        Object coerce(String raw) {
          return parsedOrBadRequest(raw, Long::parseLong, "a long integer");
        }
      },
      DOUBLE {
        @Override
        Object coerce(String raw) {
          return parsedOrBadRequest(raw, Double::parseDouble, "a double");
        }
      },
      BOOLEAN {
        @Override
        Object coerce(String raw) {
          String trimmed = raw.strip();
          if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
          }
          throw new GatewayBadRequestException("expected a boolean (true/false), got: " + raw);
        }
      };

      /**
       * Turns the raw HTTP request body into the single argument {@code
       * ModuleContext#invokeServiceByName} needs -- {@link GatewayBadRequestException} on a body
       * that doesn't match this constant's own shape, so {@link GatewayDispatcher} can report a
       * clean 400 rather than letting a {@link NumberFormatException} leak out as an opaque 500.
       */
      abstract Object coerce(String raw);

      /**
       * The Java parameter-type name {@code invokeServiceByName}'s {@code paramTypeNames} needs.
       */
      String wireTypeName() {
        return switch (this) {
          case NONE -> throw new UnsupportedOperationException("NONE has no wire type name");
          case STRING -> "java.lang.String";
          case INT -> "java.lang.Integer";
          case LONG -> "java.lang.Long";
          case DOUBLE -> "java.lang.Double";
          case BOOLEAN -> "java.lang.Boolean";
        };
      }

      private static <T> T parsedOrBadRequest(
          String raw, Function<String, T> parser, String expected) {
        try {
          return parser.apply(raw.strip());
        } catch (NumberFormatException e) {
          throw new GatewayBadRequestException("expected " + expected + ", got: " + raw);
        }
      }
    }
  }

  /**
   * An HTTP-to-vessel route: an HTTP {@code path} that, when hit, proxies the inbound request to a
   * live instance of {@code deploymentName}, on the port that instance exports under the env-var
   * name {@code portName} (see {@code VesselEnvValue.PortAllocation} in {@code gimle-core} for
   * where that name comes from, and {@link VesselEndpointCache} for how a live {@code host}/port
   * pair is resolved and cached).
   *
   * <p>The request is proxied "verbatim": whatever HTTP method, request body, and -- critically --
   * full inbound path the caller sent are forwarded to the target completely unchanged, never
   * rewritten. This holds equally for an exact-path route and a {@link #prefix()} one: unlike a
   * rewrite rule (e.g. an Ingress {@code rewrite-target} annotation, which strips the matched
   * prefix before forwarding), a plain prefix match -- the kind this route declares -- keeps the
   * full original path on the proxied call, the same as Kubernetes Ingress's own default {@code
   * pathType: Prefix} behavior. That is a deliberate choice here, not an oversight: it is exactly
   * what lets {@link GatewayDispatcher} hand {@link VesselProxyClient} the request's own untouched
   * path with no new path-rewriting logic to get wrong, for both route shapes.
   */
  record VesselRoute(
      Optional<String> host, String path, boolean prefix, String deploymentName, String portName)
      implements GatewayRoute {

    /**
     * Convenience constructor for a route with no host constraint and no prefix matching -- the
     * only shape this route kind supported before either existed, kept so every pre-existing call
     * site continues to compile and behave identically.
     */
    public VesselRoute(String path, String deploymentName, String portName) {
      this(Optional.empty(), path, false, deploymentName, portName);
    }

    public VesselRoute {
      if (host == null) {
        throw new GatewayConfigException(
            "route host must not be null (use Optional.empty() for no constraint)");
      }
      if (path == null || !path.startsWith("/")) {
        throw new GatewayConfigException("route path must start with '/': " + path);
      }
      if (prefix && path.length() > 1 && path.endsWith("/")) {
        throw new GatewayConfigException(
            "prefix route path must not end with '/' (except the root prefix '/' itself): " + path);
      }
      if (deploymentName == null || deploymentName.isBlank()) {
        throw new GatewayConfigException("route deploymentName must not be blank");
      }
      if (portName == null || portName.isBlank()) {
        throw new GatewayConfigException("route portName must not be blank");
      }
    }
  }

  /**
   * An HTTP-to-service route: an HTTP {@code path} that, when hit, proxies the inbound request to a
   * live endpoint of the control-plane-declared {@code Service} named {@code serviceName} (see
   * {@link ServiceEndpointCache} for how a live {@code host}/port pair is resolved and cached).
   * Unlike {@link VesselRoute}, there is no separate {@code portName} to name -- a {@code
   * Service}'s endpoints already carry the one port they're reachable on, the same way a Kubernetes
   * Service's own endpoint set does.
   *
   * <p>Proxying behavior (verbatim method/body, and -- for both exact and {@link #prefix()} routes
   * -- the full untouched inbound path) is otherwise identical to {@link VesselRoute} -- see that
   * record's own javadoc for exactly what "verbatim" means for a prefix match.
   */
  record ServiceRoute(Optional<String> host, String path, boolean prefix, String serviceName)
      implements GatewayRoute {

    /**
     * Convenience constructor for a route with no host constraint and no prefix matching, matching
     * the same shape {@link FabricRoute} and {@link VesselRoute} offer.
     */
    public ServiceRoute(String path, String serviceName) {
      this(Optional.empty(), path, false, serviceName);
    }

    public ServiceRoute {
      if (host == null) {
        throw new GatewayConfigException(
            "route host must not be null (use Optional.empty() for no constraint)");
      }
      if (path == null || !path.startsWith("/")) {
        throw new GatewayConfigException("route path must start with '/': " + path);
      }
      if (prefix && path.length() > 1 && path.endsWith("/")) {
        throw new GatewayConfigException(
            "prefix route path must not end with '/' (except the root prefix '/' itself): " + path);
      }
      if (serviceName == null || serviceName.isBlank()) {
        throw new GatewayConfigException("route serviceName must not be blank");
      }
    }
  }
}
