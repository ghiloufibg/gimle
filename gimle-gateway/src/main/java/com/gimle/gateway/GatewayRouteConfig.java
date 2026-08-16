package com.gimle.gateway;

import com.gimle.gateway.GatewayRoute.ParamType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses the {@code gateway.routes} config value ({@code ModuleContext#config("gateway.routes")})
 * into a {@link List} of {@link GatewayRoute}s. Deliberately the simplest format that carries what
 * a v1 fabric-only route needs -- not YAML/JSON, and not a general route DSL: one route per line,
 * five whitespace-separated fields:
 *
 * <pre>{@code
 * <httpPath> <interfaceName> <majorVersion> <methodName> <paramType>
 * }</pre>
 *
 * <p>{@code paramType} is one of {@link ParamType}'s own constant names ({@code NONE}, {@code
 * STRING}, {@code INT}, {@code LONG}, {@code DOUBLE}, {@code BOOLEAN}), matching this module's own
 * v1 restriction to zero or one simple-typed argument -- see {@link ParamType}'s own javadoc for
 * what that implies about the target method's declared signature and about HTTP verb/body shape.
 * Blank lines and lines starting with {@code #} are ignored. Two routes declaring the same {@code
 * httpPath} is a config error (which one would ever serve a request is undefined), rejected the
 * same way a malformed line is: at parse time, not discovered lazily on a request.
 *
 * <p>Example, matching {@code greeter-provider}'s own committed {@code Greeter} service:
 *
 * <pre>{@code
 * # path      interface                                    version  method  paramType
 * /greet      com.gimle.examples.greeter.Greeter            1        greet   STRING
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
    if (fields.length != 5) {
      throw new GatewayConfigException(
          "malformed route at line "
              + lineNumber
              + ": expected 5 fields (path interfaceName majorVersion methodName paramType), got "
              + fields.length
              + ": "
              + line);
    }
    String path = fields[0];
    String interfaceName = fields[1];
    int majorVersion = parseMajorVersion(fields[2], lineNumber);
    String methodName = fields[3];
    ParamType paramType = parseParamType(fields[4], lineNumber);
    try {
      return new GatewayRoute(path, interfaceName, majorVersion, methodName, paramType);
    } catch (GatewayConfigException e) {
      throw new GatewayConfigException(
          "malformed route at line " + lineNumber + ": " + e.getMessage());
    }
  }

  private static int parseMajorVersion(String raw, int lineNumber) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      throw new GatewayConfigException(
          "malformed route at line "
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
          "malformed route at line "
              + lineNumber
              + ": paramType must be one of "
              + Arrays.toString(ParamType.values())
              + ", got: "
              + raw);
    }
  }
}
