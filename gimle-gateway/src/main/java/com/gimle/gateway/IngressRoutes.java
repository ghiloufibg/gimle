package com.gimle.gateway;

import com.gimle.core.ingress.IngressRule;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the control plane's declared {@link IngressRule}s into this module's own {@link
 * GatewayRoute}s -- the declarative counterpart to {@link GatewayRouteConfig}, which parses the
 * same three route kinds out of a flat config string.
 *
 * <p>Both paths converge here on purpose. An Ingress and a {@code gateway.routes} line describe the
 * same thing, and the moment they produced two subtly different route objects, a route would behave
 * differently depending on how it was declared -- so this converts and hands off to exactly the
 * types {@link GatewayDispatcher} already dispatches, adding no route semantics of its own.
 *
 * <p>Validation is deliberately not repeated here: {@link IngressRule}'s own compact constructor
 * already rejects a rule missing the fields its kind requires, and it does so at the API boundary
 * where the operator submitting it gets told which field is wrong. What is left for this class is
 * the one thing the wire record cannot express -- {@code paramType} arrives as a string and has to
 * become a {@link ParamType} -- and an unrecognized value is rejected loudly rather than defaulted,
 * since silently choosing {@code NONE} would hand a route a signature its target method does not
 * have.
 */
public final class IngressRoutes {

  private IngressRoutes() {}

  public static List<GatewayRoute> toGatewayRoutes(List<IngressRule> rules) {
    List<GatewayRoute> routes = new ArrayList<>(rules.size());
    for (IngressRule rule : rules) {
      routes.add(toGatewayRoute(rule));
    }
    return List.copyOf(routes);
  }

  private static GatewayRoute toGatewayRoute(IngressRule rule) {
    return switch (rule.kind()) {
      case SERVICE ->
          new GatewayRoute.ServiceRoute(
              rule.host(), rule.path(), rule.prefix(), rule.serviceName().orElseThrow());
      case VESSEL ->
          new GatewayRoute.VesselRoute(
              rule.host(),
              rule.path(),
              rule.prefix(),
              rule.deploymentName().orElseThrow(),
              rule.portName().orElseThrow());
      case FABRIC ->
          new GatewayRoute.FabricRoute(
              rule.host(),
              rule.path(),
              rule.interfaceName().orElseThrow(),
              rule.majorVersion(),
              rule.methodName().orElseThrow(),
              paramType(rule));
    };
  }

  private static ParamType paramType(IngressRule rule) {
    String declared = rule.paramType().orElseThrow();
    try {
      return ParamType.valueOf(declared);
    } catch (IllegalArgumentException e) {
      throw new GatewayConfigException(
          "unknown paramType '"
              + declared
              + "' on ingress route "
              + rule.path()
              + "; expected one of NONE, STRING, INT, LONG, DOUBLE, BOOLEAN");
    }
  }
}
