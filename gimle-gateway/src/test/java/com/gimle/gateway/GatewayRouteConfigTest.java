package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.gateway.GatewayRoute.FabricRoute;
import com.gimle.gateway.GatewayRoute.FabricRoute.ParamType;
import com.gimle.gateway.GatewayRoute.VesselRoute;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouteConfigTest {

  @Test
  void parses_a_single_valid_fabric_route() {
    List<GatewayRoute> routes =
        GatewayRouteConfig.parse(
            "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING\n");

    assertEquals(1, routes.size());
    FabricRoute route = assertInstanceOf(FabricRoute.class, routes.get(0));
    assertEquals("/greet", route.path());
    assertEquals("com.gimle.examples.greeter.Greeter", route.interfaceName());
    assertEquals(1, route.majorVersion());
    assertEquals("greet", route.methodName());
    assertEquals(ParamType.STRING, route.paramType());
  }

  @Test
  void parses_a_zero_argument_fabric_route() {
    List<GatewayRoute> routes =
        GatewayRouteConfig.parse("FABRIC /ping com.gimle.examples.greeter.Greeter 1 ping NONE");

    FabricRoute route = assertInstanceOf(FabricRoute.class, routes.get(0));
    assertEquals(ParamType.NONE, route.paramType());
  }

  @Test
  void parses_a_single_valid_vessel_route() {
    List<GatewayRoute> routes =
        GatewayRouteConfig.parse("VESSEL /api/orders orders-service HTTP_PORT\n");

    assertEquals(1, routes.size());
    VesselRoute route = assertInstanceOf(VesselRoute.class, routes.get(0));
    assertEquals("/api/orders", route.path());
    assertEquals("orders-service", route.deploymentName());
    assertEquals("HTTP_PORT", route.portName());
  }

  @Test
  void parses_a_mix_of_fabric_and_vessel_routes_ignoring_blank_lines_and_comments() {
    String config =
        """
        # a comment line

        FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING
        VESSEL /api/orders orders-service HTTP_PORT
        """;

    List<GatewayRoute> routes = GatewayRouteConfig.parse(config);

    assertEquals(2, routes.size());
    assertInstanceOf(FabricRoute.class, routes.get(0));
    assertEquals("/greet", routes.get(0).path());
    assertInstanceOf(VesselRoute.class, routes.get(1));
    assertEquals("/api/orders", routes.get(1).path());
  }

  @Test
  void an_empty_config_parses_to_no_routes() {
    assertEquals(List.of(), GatewayRouteConfig.parse(""));
    assertEquals(List.of(), GatewayRouteConfig.parse("   \n  # only a comment\n"));
  }

  @Test
  void an_unknown_kind_token_is_rejected() {
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () ->
                GatewayRouteConfig.parse(
                    "HTTP /greet com.gimle.examples.greeter.Greeter 1 greet STRING"));
    assertTrue(thrown.getMessage().contains("unknown route kind"));
  }

  @Test
  void a_fabric_line_with_the_wrong_number_of_fields_is_rejected() {
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () ->
                GatewayRouteConfig.parse(
                    "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet"));
    assertTrue(thrown.getMessage().contains("line 1"));
    assertTrue(thrown.getMessage().contains("FABRIC"));
  }

  @Test
  void a_vessel_line_with_the_wrong_number_of_fields_is_rejected() {
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () -> GatewayRouteConfig.parse("VESSEL /api/orders orders-service"));
    assertTrue(thrown.getMessage().contains("line 1"));
    assertTrue(thrown.getMessage().contains("VESSEL"));
  }

  @Test
  void a_non_integer_fabric_version_is_rejected() {
    assertThrows(
        GatewayConfigException.class,
        () ->
            GatewayRouteConfig.parse(
                "FABRIC /greet com.gimle.examples.greeter.Greeter not-a-number greet STRING"));
  }

  @Test
  void a_fabric_param_type_outside_the_v1_restriction_is_rejected_at_parse_time() {
    // "OBJECT" is not one of ParamType's constants -- this is exactly the "a route naming a param
    // type outside the v1 restriction" case, rejected here, at config-parse time, rather than
    // silently accepted and only failing later on a real request.
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () ->
                GatewayRouteConfig.parse(
                    "FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet OBJECT"));
    assertTrue(thrown.getMessage().contains("paramType"));
  }

  @Test
  void a_fabric_path_not_starting_with_a_slash_is_rejected() {
    assertThrows(
        GatewayConfigException.class,
        () ->
            GatewayRouteConfig.parse(
                "FABRIC greet com.gimle.examples.greeter.Greeter 1 greet STRING"));
  }

  @Test
  void a_vessel_path_not_starting_with_a_slash_is_rejected() {
    assertThrows(
        GatewayConfigException.class,
        () -> GatewayRouteConfig.parse("VESSEL api/orders orders-service HTTP_PORT"));
  }

  @Test
  void a_blank_vessel_deployment_name_is_rejected() {
    // split("\\s+") never produces an empty field from whitespace-separated input, so the only way
    // to exercise VesselRoute's own blank-deploymentName check through the config parser is a
    // deployment name that is present but entirely non-content -- not reachable via whitespace
    // splitting, so this goes straight at the record's own constructor instead.
    assertThrows(
        GatewayConfigException.class, () -> new VesselRoute("/api/orders", "", "HTTP_PORT"));
  }

  @Test
  void a_blank_vessel_port_name_is_rejected() {
    assertThrows(
        GatewayConfigException.class, () -> new VesselRoute("/api/orders", "orders-service", ""));
  }

  @Test
  void a_duplicate_route_path_across_fabric_and_vessel_is_rejected() {
    String config =
        """
        FABRIC /api/orders com.gimle.examples.greeter.Greeter 1 greet STRING
        VESSEL /api/orders orders-service HTTP_PORT
        """;

    assertThrows(GatewayConfigException.class, () -> GatewayRouteConfig.parse(config));
  }

  @Test
  void a_duplicate_fabric_route_path_is_rejected() {
    String config =
        """
        FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING
        FABRIC /greet com.gimle.examples.greeter.Greeter 1 greetLoudly STRING
        """;

    assertThrows(GatewayConfigException.class, () -> GatewayRouteConfig.parse(config));
  }

  @Test
  void a_duplicate_vessel_route_path_is_rejected() {
    String config =
        """
        VESSEL /api/orders orders-service HTTP_PORT
        VESSEL /api/orders other-service HTTP_PORT
        """;

    assertThrows(GatewayConfigException.class, () -> GatewayRouteConfig.parse(config));
  }
}
