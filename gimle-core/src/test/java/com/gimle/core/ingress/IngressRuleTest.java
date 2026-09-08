package com.gimle.core.ingress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class IngressRuleTest {

  @Test
  void a_fabric_route_with_a_plain_path_succeeds() {
    assertDoesNotThrow(
        () ->
            IngressRule.fabric(
                Optional.empty(), "/greet", "com.acme.Greeter", 1, "greet", "STRING"));
  }

  @Test
  void a_fabric_route_declaring_a_prefix_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new IngressRule(
                Optional.empty(),
                "/greet",
                true,
                IngressRule.Kind.FABRIC,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("com.acme.Greeter"),
                1,
                Optional.of("greet"),
                Optional.of("STRING")));
  }

  @Test
  void a_fabric_route_with_a_wildcard_suffixed_path_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            IngressRule.fabric(
                Optional.empty(), "/greet/*", "com.acme.Greeter", 1, "greet", "STRING"));
  }

  @Test
  void a_vessel_route_with_a_wildcard_suffixed_path_is_not_rejected_by_the_fabric_only_check() {
    assertDoesNotThrow(
        () -> IngressRule.vessel(Optional.empty(), "/api/*", true, "orders", "http"));
  }
}
