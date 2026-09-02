package com.gimle.controlplane.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.StateStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Both advisory conditions are deliberately non-fatal, so every test here asserts on the returned
 * warning text -- there is no rejection path to assert instead.
 */
class ServiceAdvisoriesTest {

  private static final ModuleId MODULE_ID = new ModuleId("com.acme.orders", Version.parse("1.0.0"));

  private static void recordInstance(
      StateStore store, String deploymentName, Map<String, Integer> ports) {
    store.putAssignment(new InstanceAssignment(deploymentName, 0, "node-a", MODULE_ID, ""));
    store.putNodeHeartbeat(
        new NodeHeartbeat(
            "node-a",
            new ResourceUsageSnapshot(0, 0, 0, 0),
            List.of(
                InstanceObservation.builder(deploymentName, 0, MODULE_ID, "ACTIVE", true, true)
                    .ports(ports)
                    .build())));
  }

  @Test
  void an_overlapping_deployment_name_in_the_same_tenant_is_reported() {
    ServiceSpec existing =
        new ServiceSpec("orders-legacy", Optional.empty(), Set.of("orders-service"), 8081);
    ServiceSpec submitted =
        new ServiceSpec(
            "orders", Optional.empty(), Set.of("orders-service", "orders-canary"), 8080);

    List<String> warnings =
        ServiceAdvisories.forSubmission(submitted, List.of(existing), new StateStore());

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("orders-legacy"), warnings.get(0));
    assertTrue(warnings.get(0).contains("orders-service"), warnings.get(0));
    assertTrue(
        !warnings.get(0).contains("orders-canary"),
        "only the genuinely shared deployment names belong in the warning: " + warnings.get(0));
  }

  @Test
  void every_overlapping_service_gets_its_own_warning() {
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    List<String> warnings =
        ServiceAdvisories.forSubmission(
            submitted,
            List.of(
                new ServiceSpec("b-legacy", Optional.empty(), Set.of("orders-service"), 8081),
                new ServiceSpec("a-legacy", Optional.empty(), Set.of("orders-service"), 8082)),
            new StateStore());

    assertEquals(2, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("a-legacy"), "warnings are name-ordered: " + warnings);
    assertTrue(warnings.get(1).contains("b-legacy"), warnings.toString());
  }

  @Test
  void a_service_re_submitted_under_its_own_name_does_not_overlap_itself() {
    ServiceSpec spec = new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(List.of(), ServiceAdvisories.forSubmission(spec, List.of(spec), new StateStore()));
  }

  @Test
  void the_same_deployment_name_under_a_different_tenant_is_a_different_deployment() {
    ServiceSpec existing =
        new ServiceSpec("web", Optional.of("tenant-a"), Set.of("orders-service"), 8081);
    ServiceSpec submitted =
        new ServiceSpec("web2", Optional.of("tenant-b"), Set.of("orders-service"), 8080);

    assertEquals(
        List.of(), ServiceAdvisories.forSubmission(submitted, List.of(existing), new StateStore()));
  }

  @Test
  void disjoint_deployment_names_earn_no_warning() {
    ServiceSpec existing =
        new ServiceSpec("billing", Optional.empty(), Set.of("billing-service"), 8081);
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(
        List.of(), ServiceAdvisories.forSubmission(submitted, List.of(existing), new StateStore()));
  }

  @Test
  void a_target_port_no_backing_instance_reports_is_reported() {
    StateStore store = new StateStore();
    recordInstance(store, "orders-service", Map.of("HTTP_PORT", 51234));
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090);

    List<String> warnings = ServiceAdvisories.forSubmission(submitted, List.of(), store);

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("9090"), warnings.get(0));
    assertTrue(warnings.get(0).contains("51234"), warnings.get(0));
  }

  @Test
  void a_target_port_some_backing_instance_does_report_is_not_reported() {
    StateStore store = new StateStore();
    recordInstance(store, "orders-service", Map.of("HTTP_PORT", 51234, "ADMIN_PORT", 9090));
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090);

    assertEquals(List.of(), ServiceAdvisories.forSubmission(submitted, List.of(), store));
  }

  /** Declaring a Service ahead of the workload behind it is normal, not a misconfiguration. */
  @Test
  void a_target_port_with_no_backing_instance_at_all_is_not_reported() {
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080, 9090);

    assertEquals(
        List.of(), ServiceAdvisories.forSubmission(submitted, List.of(), new StateStore()));
  }

  @Test
  void a_service_declaring_no_target_port_is_never_warned_about_one() {
    StateStore store = new StateStore();
    recordInstance(store, "orders-service", Map.of("HTTP_PORT", 51234));
    ServiceSpec submitted =
        new ServiceSpec("orders", Optional.empty(), Set.of("orders-service"), 8080);

    assertEquals(List.of(), ServiceAdvisories.forSubmission(submitted, List.of(), store));
  }

  @Test
  void an_external_name_service_is_never_warned_about_a_target_port() {
    ServiceSpec submitted =
        new ServiceSpec(
            "billing",
            Optional.empty(),
            Set.of(),
            443,
            OptionalInt.of(8443),
            false,
            Optional.of("billing.example.com"));

    assertEquals(
        List.of(), ServiceAdvisories.forSubmission(submitted, List.of(), new StateStore()));
  }
}
