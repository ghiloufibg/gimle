package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.ingress.IngressRule;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Converting declared {@link IngressRule}s into the gateway's own routes. The property that matters
 * is that a route behaves identically however it was declared -- an Ingress and a {@code
 * gateway.routes} line must produce the same route object, not two subtly different ones.
 */
class IngressRoutesTest {

  @Test
  void a_service_rule_becomes_the_same_route_the_config_parser_produces() {
    List<GatewayRoute> routes =
        IngressRoutes.toGatewayRoutes(
            List.of(IngressRule.service(Optional.empty(), "/api", false, "orders")));

    assertEquals(GatewayRouteConfig.parse("SERVICE /api orders"), routes);
  }

  @Test
  void a_vessel_rule_becomes_the_same_route_the_config_parser_produces() {
    List<GatewayRoute> routes =
        IngressRoutes.toGatewayRoutes(
            List.of(IngressRule.vessel(Optional.empty(), "/app", false, "billing", "HTTP_PORT")));

    assertEquals(GatewayRouteConfig.parse("VESSEL /app billing HTTP_PORT"), routes);
  }

  @Test
  void a_fabric_rule_becomes_the_same_route_the_config_parser_produces() {
    List<GatewayRoute> routes =
        IngressRoutes.toGatewayRoutes(
            List.of(
                IngressRule.fabric(
                    Optional.empty(), "/greet", "com.acme.Greeter", 1, "greet", "STRING")));

    assertEquals(GatewayRouteConfig.parse("FABRIC /greet com.acme.Greeter 1 greet STRING"), routes);
  }

  @Test
  void a_host_constraint_and_a_prefix_both_survive_the_conversion() {
    List<GatewayRoute> routes =
        IngressRoutes.toGatewayRoutes(
            List.of(IngressRule.service(Optional.of("shop.example"), "/api", true, "orders")));

    GatewayRoute route = routes.get(0);
    assertEquals(Optional.of("shop.example"), route.host());
    assertTrue(route.prefix());
    assertEquals(GatewayRouteConfig.parse("HOST shop.example SERVICE /api/* orders"), routes);
  }

  @Test
  void an_unknown_param_type_is_rejected_rather_than_defaulted() {
    // Defaulting to NONE would hand the route a signature the target method does not have, which
    // fails at call time with something far less obvious than this.
    List<IngressRule> rules =
        List.of(
            IngressRule.fabric(
                Optional.empty(), "/greet", "com.acme.Greeter", 1, "greet", "NOT_A_TYPE"));

    GatewayConfigException e =
        assertThrows(GatewayConfigException.class, () -> IngressRoutes.toGatewayRoutes(rules));
    assertTrue(e.getMessage().contains("NOT_A_TYPE"));
  }

  @Test
  void every_declared_param_type_converts() {
    for (ParamType type : ParamType.values()) {
      List<GatewayRoute> routes =
          IngressRoutes.toGatewayRoutes(
              List.of(
                  IngressRule.fabric(Optional.empty(), "/p", "com.acme.Svc", 1, "m", type.name())));
      GatewayRoute.FabricRoute route =
          assertInstanceOf(GatewayRoute.FabricRoute.class, routes.get(0));
      assertEquals(type, route.paramType());
    }
  }

  @Test
  void an_empty_declaration_yields_no_routes() {
    assertTrue(IngressRoutes.toGatewayRoutes(List.of()).isEmpty());
  }
}
