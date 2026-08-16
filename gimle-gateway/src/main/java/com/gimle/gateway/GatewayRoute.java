package com.gimle.gateway;

import java.util.function.Function;

/**
 * One declared gateway route: an HTTP {@code path} that, when hit, is served one of two ways.
 * {@link FabricRoute} invokes a named fabric service via {@code ModuleContext#invokeServiceByName}
 * -- the only kind this module originally supported. {@link VesselRoute} instead proxies the
 * inbound request to a live instance of a named deployment, resolved through {@code
 * ModuleContext#relayControlPlaneRead} (see {@link VesselEndpointCache}). The two kinds share
 * nothing beyond an HTTP path to dispatch on, which is why this is a sealed interface of two record
 * shapes rather than one flat record trying to cover both -- see {@link GatewayDispatcher#dispatch}
 * for how each kind is actually served.
 */
public sealed interface GatewayRoute permits GatewayRoute.FabricRoute, GatewayRoute.VesselRoute {

  /** The HTTP path this route answers -- exact-match only, see {@link GatewayDispatcher}. */
  String path();

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
      String path, String interfaceName, int majorVersion, String methodName, ParamType paramType)
      implements GatewayRoute {

    public FabricRoute {
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
   * An HTTP-to-vessel route: an HTTP {@code path} that, when hit, proxies the inbound request
   * verbatim to a live instance of {@code deploymentName}, on the port that instance exports under
   * the env-var name {@code portName} (see {@code VesselEnvValue.PortAllocation} in {@code
   * gimle-core} for where that name comes from, and {@link VesselEndpointCache} for how a live
   * {@code host}/port pair is resolved and cached).
   *
   * <p>"Verbatim" means exact-path, no rewriting: this route's own {@code path} is forwarded to the
   * target unchanged (never a prefix stripped or a wildcard expanded -- deliberately out of scope
   * for v1, see {@link GatewayDispatcher}'s own javadoc), and every HTTP method plus the full
   * request body pass through unchanged, unlike a {@link FabricRoute}'s method/argument-shape
   * restrictions.
   */
  record VesselRoute(String path, String deploymentName, String portName) implements GatewayRoute {

    public VesselRoute {
      if (path == null || !path.startsWith("/")) {
        throw new GatewayConfigException("route path must start with '/': " + path);
      }
      if (deploymentName == null || deploymentName.isBlank()) {
        throw new GatewayConfigException("route deploymentName must not be blank");
      }
      if (portName == null || portName.isBlank()) {
        throw new GatewayConfigException("route portName must not be blank");
      }
    }
  }
}
