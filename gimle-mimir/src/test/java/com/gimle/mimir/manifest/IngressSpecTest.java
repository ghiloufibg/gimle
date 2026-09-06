package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.ingress.IngressRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * An Ingress validates its own routes where it is built, which is the point at which a submission
 * is still attributable to whoever made it -- a route accepted here and refused later is refused
 * against a gateway that did nothing wrong.
 */
class IngressSpecTest {

  private static IngressSpec fabricIngressWithParamType(String paramType) {
    return new IngressSpec(
        "public",
        "acme",
        List.of(
            IngressRule.fabric(
                Optional.empty(), "/greet", "com.acme.Greeter", 1, "greet", paramType)));
  }

  @Test
  void a_fabric_route_naming_an_unknown_param_type_is_refused_naming_the_valid_values() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> fabricIngressWithParamType("STRINGG"));

    assertTrue(thrown.getMessage().contains("STRINGG"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("/greet"), thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains("NONE, STRING, INT, LONG, DOUBLE, BOOLEAN"),
        thrown.getMessage());
  }

  @Test
  void a_param_type_is_matched_exactly_and_never_case_folded() {
    assertThrows(IllegalArgumentException.class, () -> fabricIngressWithParamType("string"));
  }

  @Test
  void every_supported_param_type_is_accepted() {
    for (String paramType : List.of("NONE", "STRING", "INT", "LONG", "DOUBLE", "BOOLEAN")) {
      assertEquals(
          Optional.of(paramType),
          fabricIngressWithParamType(paramType).routes().get(0).paramType());
    }
  }

  @Test
  void a_route_kind_that_declares_no_param_type_at_all_is_untouched_by_the_check() {
    IngressSpec spec =
        new IngressSpec(
            "public",
            "acme",
            List.of(IngressRule.service(Optional.empty(), "/api", true, "orders")));

    assertEquals(Optional.empty(), spec.routes().get(0).paramType());
  }
}
