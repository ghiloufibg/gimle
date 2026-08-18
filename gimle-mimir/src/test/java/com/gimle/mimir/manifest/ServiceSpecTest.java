package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServiceSpecTest {

  @Test
  void rejects_a_blank_name() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec(" ", Optional.empty(), Set.of("orders-service"), 8080));
  }

  @Test
  void rejects_a_null_deployment_names() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), null, 8080));
  }

  @Test
  void rejects_an_empty_deployment_names() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), Set.of(), 8080));
  }

  @Test
  void rejects_a_blank_entry_in_deployment_names() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), Set.of("orders-service", " "), 8080));
  }

  @Test
  void rejects_a_port_out_of_range() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 70000));
  }

  @Test
  void the_four_arg_constructor_defaults_target_port_to_port() {
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(8080, spec.port());
    assertEquals(8080, spec.targetPort());
  }

  @Test
  void port_and_target_port_may_differ() {
    ServiceSpec spec =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090);

    assertEquals(8080, spec.port());
    assertEquals(9090, spec.targetPort());
  }

  @Test
  void deployment_names_is_defensively_copied_and_immutable() {
    var mutable = new java.util.HashSet<String>();
    mutable.add("orders-service");
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), mutable, 8080);
    mutable.add("orders-service-canary");

    assertEquals(Set.of("orders-service"), spec.deploymentNames());
    assertThrows(UnsupportedOperationException.class, () -> spec.deploymentNames().add("x"));
  }
}
