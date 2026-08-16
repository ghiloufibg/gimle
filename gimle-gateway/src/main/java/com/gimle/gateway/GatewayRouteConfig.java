package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses the {@code gateway.routes} config value ({@code ModuleContext#config("gateway.routes")})
 * into a {@link List} of {@link GatewayRoute}s. Deliberately the simplest format that carries what
 * this module's two route kinds need -- not YAML/JSON, and not a general route DSL: one route per
 * line, starting with an explicit kind token:
 *
 * <pre>{@code
 * FABRIC <httpPath> <interfaceName> <majorVersion> <methodName> <paramType>
 * VESSEL <httpPath> <deploymentName> <portName>
 * }</pre>
 *
 * <p>A {@code FABRIC} line's remaining five fields are exactly {@link GatewayRoute.FabricRoute}'s
 * own fields -- {@code paramType} is one of {@link ParamType}'s own constant names ({@code NONE},
 * {@code STRING}, {@code INT}, {@code LONG}, {@code DOUBLE}, {@code BOOLEAN}), matching this
 * module's own v1 restriction to zero or one simple-typed argument -- see {@link ParamType}'s own
 * javadoc for what that implies about the target method's declared signature and about HTTP
 * verb/body shape. A {@code VESSEL} line's remaining three fields are exactly {@link
 * GatewayRoute.VesselRoute}'s own fields. Blank lines and lines starting with {@code #} are
 * ignored. Two routes declaring the same {@code httpPath} is a config error regardless of either
 * route's kind (which one would ever serve a request is undefined), rejected the same way a
 * malformed line is: at parse time, not discovered lazily on a request.
 *
 * <p>Example, matching {@code greeter-provider}'s own committed {@code Greeter} service and a
 * hypothetical {@code orders-service} vessel deployment:
 *
 * <pre>{@code
 * # kind    path         interface/deployment                          version  method  paramType
 * FABRIC    /greet       com.gimle.examples.greeter.Greeter            1        greet   STRING
 * VESSEL    /api/orders  orders-service                                HTTP_PORT
 * }</pre>
 */
public final class GatewayRouteConfig {

  private GatewayRouteConfig() {}

  public static List<GatewayRoute> parse(String text) {
    List<GatewayRoute> routes = new ArrayList<>();
    List<String> seenPaths = new ArrayList<>();
    String[] lines = text.split("\n", -1);
    for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
      String line = lines[lineNumber - 1].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      GatewayRoute route = parseLine(line, lineNumber);
      if (seenPaths.contains(route.path())) {
        throw new GatewayConfigException(
            "duplicate route path '" + route.path() + "' at line " + lineNumber);
      }
      seenPaths.add(route.path());
      routes.add(route);
    }
    return List.copyOf(routes);
  }

  private static GatewayRoute parseLine(String line, int lineNumber) {
    String[] fields = line.split("\\s+");
    String kind = fields[0];
    return switch (kind) {
      case "FABRIC" -> parseFabricLine(fields, lineNumber, line);
      case "VESSEL" -> parseVesselLine(fields, lineNumber, line);
      default ->
          throw new GatewayConfigException(
              "malformed route at line "
                  + lineNumber
                  + ": unknown route kind '"
                  + kind
                  + "', expected FABRIC or VESSEL: "
                  + line);
    };
  }

  private static GatewayRoute.FabricRoute parseFabricLine(
      String[] fields, int lineNumber, String line) {
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
      return new GatewayRoute.FabricRoute(path, interfaceName, majorVersion, methodName, paramType);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed FABRIC route at line " + lineNumber + ": " + e.getMessage());
    }
  }

  private static GatewayRoute.VesselRoute parseVesselLine(
      String[] fields, int lineNumber, String line) {
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
      return new GatewayRoute.VesselRoute(path, deploymentName, portName);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed VESSEL route at line " + lineNumber + ": " + e.getMessage());
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
}
