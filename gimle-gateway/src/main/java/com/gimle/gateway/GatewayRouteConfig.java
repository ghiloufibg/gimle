package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the {@code gateway.routes} config value ({@code ModuleContext#config("gateway.routes")})
 * into a {@link List} of {@link GatewayRoute}s. Deliberately the simplest format that carries what
 * this module's route kinds need -- not YAML/JSON, and not a general route DSL: one route per line,
 * an optional leading {@code HOST <hostname>} segment, then an explicit kind token:
 *
 * <pre>{@code
 * [HOST <hostname>] FABRIC <httpPath> <interfaceName> <majorVersion> <methodName> <paramType>
 * [HOST <hostname>] VESSEL <httpPath> <deploymentName> <portName>
 * [HOST <hostname>] SERVICE <httpPath> <serviceName>
 * }</pre>
 *
 * <p>The {@code HOST} segment is additive: a line with no {@code HOST} segment behaves exactly as
 * it always has, matching a request on any host -- see {@link GatewayDispatcher} for the matching
 * precedence between a host-constrained route and a host-unconstrained one sharing the same path. A
 * {@code FABRIC} line's remaining five fields are exactly {@link GatewayRoute.FabricRoute}'s own
 * fields (besides {@code host}) -- {@code paramType} is one of {@link ParamType}'s own constant
 * names ({@code NONE}, {@code STRING}, {@code INT}, {@code LONG}, {@code DOUBLE}, {@code BOOLEAN}),
 * matching this module's own v1 restriction to zero or one simple-typed argument -- see {@link
 * ParamType}'s own javadoc for what that implies about the target method's declared signature and
 * about HTTP verb/body shape. A {@code VESSEL} line's remaining three fields are exactly {@link
 * GatewayRoute.VesselRoute}'s own fields (besides {@code host}). A {@code SERVICE} line's remaining
 * two fields are exactly {@link GatewayRoute.ServiceRoute}'s own fields (besides {@code host}) -- a
 * control-plane-declared {@code Service} name, resolved and proxied to at request time (see {@link
 * ServiceEndpointCache}). Blank lines and lines starting with {@code #} are ignored.
 *
 * <p>Two routes declaring the same {@code httpPath} and the same host constraint (including two
 * routes both leaving the host unconstrained) is a config error regardless of either route's kind
 * (which one would ever serve a request is undefined), rejected the same way a malformed line is:
 * at parse time, not discovered lazily on a request. Two routes at the same {@code httpPath} with
 * *different* host constraints are not a duplicate -- that's the ordinary virtual-hosting shape,
 * one route per host sharing a path, optionally with one further host-unconstrained route at that
 * path as a default/fallback for a host matching none of the others.
 *
 * <p>Example, matching {@code greeter-provider}'s own committed {@code Greeter} service, a
 * hypothetical {@code orders-service} vessel deployment restricted to one virtual host, and a
 * hypothetical {@code payments} control-plane {@code Service}:
 *
 * <pre>{@code
 * # kind    path         interface/deployment/service                  version  method  paramType
 * FABRIC    /greet       com.gimle.examples.greeter.Greeter            1        greet   STRING
 * HOST orders.example.com VESSEL /api/orders orders-service HTTP_PORT
 * SERVICE   /api/payments payments
 * }</pre>
 */
public final class GatewayRouteConfig {

  private GatewayRouteConfig() {}

  public static List<GatewayRoute> parse(String text) {
    List<GatewayRoute> routes = new ArrayList<>();
    Set<RouteKey> seenKeys = new HashSet<>();
    String[] lines = text.split("\n", -1);
    for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
      String line = lines[lineNumber - 1].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      GatewayRoute route = parseLine(line, lineNumber);
      RouteKey key = new RouteKey(route.path(), route.host().map(String::toLowerCase));
      if (!seenKeys.add(key)) {
        String hostSuffix = route.host().map(h -> " for host '" + h + "'").orElse("");
        throw new GatewayConfigException(
            "duplicate route path '" + route.path() + "'" + hostSuffix + " at line " + lineNumber);
      }
      routes.add(route);
    }
    return List.copyOf(routes);
  }

  private static GatewayRoute parseLine(String line, int lineNumber) {
    String[] fields = line.split("\\s+");
    Optional<String> host = Optional.empty();
    if (fields.length > 0 && "HOST".equals(fields[0])) {
      if (fields.length < 2 || fields[1].isBlank()) {
        throw new GatewayConfigException(
            "malformed route at line "
                + lineNumber
                + ": HOST must be followed by a hostname: "
                + line);
      }
      host = Optional.of(fields[1]);
      fields = Arrays.copyOfRange(fields, 2, fields.length);
    }
    if (fields.length == 0) {
      throw new GatewayConfigException(
          "malformed route at line " + lineNumber + ": missing route kind after HOST: " + line);
    }
    String kind = fields[0];
    return switch (kind) {
      case "FABRIC" -> parseFabricLine(fields, lineNumber, line, host);
      case "VESSEL" -> parseVesselLine(fields, lineNumber, line, host);
      case "SERVICE" -> parseServiceLine(fields, lineNumber, line, host);
      default ->
          throw new GatewayConfigException(
              "malformed route at line "
                  + lineNumber
                  + ": unknown route kind '"
                  + kind
                  + "', expected FABRIC, VESSEL, or SERVICE: "
                  + line);
    };
  }

  private static GatewayRoute.FabricRoute parseFabricLine(
      String[] fields, int lineNumber, String line, Optional<String> host) {
    if (fields.length != 6) {
      throw new GatewayConfigException(
          "malformed FABRIC route at line "
              + lineNumber
              + ": expected 6 fields (FABRIC path interfaceName majorVersion methodName"
              + " paramType), got "
              + fields.length
              + ": "
              + line);
    }
    String path = fields[1];
    String interfaceName = fields[2];
    int majorVersion = parseMajorVersion(fields[3], lineNumber);
    String methodName = fields[4];
    ParamType paramType = parseParamType(fields[5], lineNumber);
    try {
      return new GatewayRoute.FabricRoute(
          host, path, interfaceName, majorVersion, methodName, paramType);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed FABRIC route at line " + lineNumber + ": " + e.getMessage());
    }
  }

  private static GatewayRoute.VesselRoute parseVesselLine(
      String[] fields, int lineNumber, String line, Optional<String> host) {
    if (fields.length != 4) {
      throw new GatewayConfigException(
          "malformed VESSEL route at line "
              + lineNumber
              + ": expected 4 fields (VESSEL path deploymentName portName), got "
              + fields.length
              + ": "
              + line);
    }
    String path = fields[1];
    String deploymentName = fields[2];
    String portName = fields[3];
    try {
      return new GatewayRoute.VesselRoute(host, path, deploymentName, portName);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed VESSEL route at line " + lineNumber + ": " + e.getMessage());
    }
  }

  private static GatewayRoute.ServiceRoute parseServiceLine(
      String[] fields, int lineNumber, String line, Optional<String> host) {
    if (fields.length != 3) {
      throw new GatewayConfigException(
          "malformed SERVICE route at line "
              + lineNumber
              + ": expected 3 fields (SERVICE path serviceName), got "
              + fields.length
              + ": "
              + line);
    }
    String path = fields[1];
    String serviceName = fields[2];
    try {
      return new GatewayRoute.ServiceRoute(host, path, serviceName);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed SERVICE route at line " + lineNumber + ": " + e.getMessage());
    }
  }

  private static int parseMajorVersion(String raw, int lineNumber) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new GatewayConfigException(
          "malformed FABRIC route at line "
              + lineNumber
              + ": majorVersion must be an integer, got: "
              + raw);
    }
  }

  private static ParamType parseParamType(String raw, int lineNumber) {
    try {
      return ParamType.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new GatewayConfigException(
          "malformed FABRIC route at line "
              + lineNumber
              + ": paramType must be one of "
              + Arrays.toString(ParamType.values())
              + ", got: "
              + raw);
    }
  }

  /**
   * Uniqueness key for duplicate-route detection: a path plus its (lower-cased) host constraint.
   */
  private record RouteKey(String path, Optional<String> host) {}
}
