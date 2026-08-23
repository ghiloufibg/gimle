package com.gimle.mimir.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ControllerRevisionTest {

  private static final ModuleId ORDERS =
      new ModuleId("com.gimle.example.orders", Version.parse("1.0.0"));

  private static DeploymentSpec deploymentSpec(String name) {
    return new DeploymentSpec(
        name, ORDERS, "/var/gimle/artifacts/orders-1.0.0.jar", 1, PlacementConstraints.NONE);
  }

  @Test
  void rejects_a_blank_workload_kind() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "",
                "orders-service",
                1,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.empty()));
  }

  @Test
  void rejects_a_workload_kind_that_is_not_deployment_statefulset_or_daemonset() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "Job",
                "orders-service",
                1,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.empty()));
  }

  @Test
  void rejects_a_spec_that_does_not_match_the_declared_workload_kind() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "StatefulSet",
                "orders-service",
                1,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.empty()));
  }

  @Test
  void rejects_a_spec_whose_own_name_does_not_match_the_revision_name() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "Deployment",
                "a-different-name",
                1,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.empty()));
  }

  @Test
  void rejects_a_non_positive_revision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "Deployment",
                "orders-service",
                0,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.empty()));
  }

  @Test
  void rejects_a_non_positive_rollback_of_revision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "Deployment",
                "orders-service",
                2,
                deploymentSpec("orders-service"),
                1_000L,
                OptionalInt.of(0)));
  }

  @Test
  void rejects_a_null_rollback_of_revision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ControllerRevision(
                "Deployment", "orders-service", 1, deploymentSpec("orders-service"), 1_000L, null));
  }

  @Test
  void accepts_a_matching_deployment_spec() {
    ControllerRevision revision =
        new ControllerRevision(
            "Deployment",
            "orders-service",
            1,
            deploymentSpec("orders-service"),
            1_000L,
            OptionalInt.empty());

    assertEquals("Deployment", revision.workloadKind());
    assertEquals("orders-service", revision.name());
  }

  @Test
  void revision_key_combines_workload_kind_and_name_so_kinds_never_collide() {
    assertEquals(
        "Deployment#orders-service",
        ControllerRevision.revisionKey("Deployment", "orders-service"));
    assertEquals(
        "StatefulSet#orders-service",
        ControllerRevision.revisionKey("StatefulSet", "orders-service"));
  }
}
