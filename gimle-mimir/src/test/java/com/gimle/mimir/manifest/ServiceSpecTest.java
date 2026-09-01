package com.gimle.mimir.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.OptionalInt;
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
  void the_four_arg_constructor_declares_no_target_port_at_all() {
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(8080, spec.port());
    assertEquals(OptionalInt.empty(), spec.targetPort());
  }

  @Test
  void port_and_target_port_may_differ() {
    ServiceSpec spec =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090);

    assertEquals(8080, spec.port());
    assertEquals(OptionalInt.of(9090), spec.targetPort());
  }

  @Test
  void rejects_a_null_target_port() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ServiceSpec(
                "orders",
                Optional.empty(),
                Set.of("orders-service"),
                8080,
                null,
                false,
                Optional.empty()));
  }

  @Test
  void rejects_a_declared_target_port_out_of_range() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 70000));
  }

  @Test
  void an_external_name_service_names_no_deployments_and_reports_itself() {
    ServiceSpec spec =
        new ServiceSpec(
            "billing",
            Optional.empty(),
            Set.of(),
            443,
            OptionalInt.of(443),
            false,
            Optional.of("billing.example.com"));

    assertEquals(Optional.of("billing.example.com"), spec.externalName());
    assertEquals(Set.of(), spec.deploymentNames());
    assertEquals(true, spec.isExternalName());
  }

  @Test
  void an_external_name_service_must_not_also_name_deployments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ServiceSpec(
                "billing",
                Optional.empty(),
                Set.of("orders-service"),
                443,
                OptionalInt.of(443),
                false,
                Optional.of("billing.example.com")));
  }

  @Test
  void a_blank_external_name_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ServiceSpec(
                "billing",
                Optional.empty(),
                Set.of(),
                443,
                OptionalInt.of(443),
                false,
                Optional.of(" ")));
  }

  @Test
  void session_affinity_defaults_off_via_the_convenience_constructors() {
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(false, spec.sessionAffinity());
    assertEquals(Optional.empty(), spec.externalName());
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
