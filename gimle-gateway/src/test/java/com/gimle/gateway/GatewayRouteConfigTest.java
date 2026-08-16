package com.gimle.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.gateway.GatewayRoute.ParamType;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouteConfigTest {

  @Test
  void parses_a_single_valid_route() {
    List<GatewayRoute> routes =
        GatewayRouteConfig.parse("/greet com.gimle.examples.greeter.Greeter 1 greet STRING\n");

    assertEquals(1, routes.size());
    GatewayRoute route = routes.get(0);
    assertEquals("/greet", route.path());
    assertEquals("com.gimle.examples.greeter.Greeter", route.interfaceName());
    assertEquals(1, route.majorVersion());
    assertEquals("greet", route.methodName());
    assertEquals(ParamType.STRING, route.paramType());
  }

  @Test
  void parses_a_zero_argument_route() {
    List<GatewayRoute> routes =
        GatewayRouteConfig.parse("/ping com.gimle.examples.greeter.Greeter 1 ping NONE");

    assertEquals(ParamType.NONE, routes.get(0).paramType());
  }

  @Test
  void parses_multiple_routes_ignoring_blank_lines_and_comments() {
    String config =
        """
        # a comment line

        /greet com.gimle.examples.greeter.Greeter 1 greet STRING
        /ping  com.gimle.examples.greeter.Greeter 1 ping  NONE
        """;

    List<GatewayRoute> routes = GatewayRouteConfig.parse(config);

    assertEquals(2, routes.size());
    assertEquals("/greet", routes.get(0).path());
    assertEquals("/ping", routes.get(1).path());
  }

  @Test
  void an_empty_config_parses_to_no_routes() {
    assertEquals(List.of(), GatewayRouteConfig.parse(""));
    assertEquals(List.of(), GatewayRouteConfig.parse("   \n  # only a comment\n"));
  }

  @Test
  void a_line_with_the_wrong_number_of_fields_is_rejected() {
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () -> GatewayRouteConfig.parse("/greet com.gimle.examples.greeter.Greeter 1 greet"));
    assertTrue(thrown.getMessage().contains("line 1"));
  }

  @Test
  void a_non_integer_version_is_rejected() {
    assertThrows(
        GatewayConfigException.class,
        () ->
            GatewayRouteConfig.parse(
                "/greet com.gimle.examples.greeter.Greeter not-a-number greet STRING"));
  }

  @Test
  void a_param_type_outside_the_v1_restriction_is_rejected_at_parse_time() {
    // "OBJECT" is not one of ParamType's constants -- this is exactly the "a route naming a param
    // type outside the v1 restriction" case, rejected here, at config-parse time, rather than
    // silently accepted and only failing later on a real request.
    GatewayConfigException thrown =
        assertThrows(
            GatewayConfigException.class,
            () ->
                GatewayRouteConfig.parse(
                    "/greet com.gimle.examples.greeter.Greeter 1 greet OBJECT"));
    assertTrue(thrown.getMessage().contains("paramType"));
  }

  @Test
  void a_path_not_starting_with_a_slash_is_rejected() {
    assertThrows(
        GatewayConfigException.class,
        () -> GatewayRouteConfig.parse("greet com.gimle.examples.greeter.Greeter 1 greet STRING"));
  }

  @Test
  void a_duplicate_route_path_is_rejected() {
    String config =
        """
        /greet com.gimle.examples.greeter.Greeter 1 greet STRING
        /greet com.gimle.examples.greeter.Greeter 1 greetLoudly STRING
        """;

    assertThrows(GatewayConfigException.class, () -> GatewayRouteConfig.parse(config));
  }
}
