package com.gimle.hugin.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.cli.CliException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reading the control plane's account of itself, including when it cannot give one. */
class PulseReaderTest {

  @Test
  void a_healthy_control_plane_reports_its_own_uptime_transport_and_store_reach() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject(
                "/health",
                Map.of(
                    "status",
                    "UP",
                    "uptimeSeconds",
                    7200,
                    "transportProtocol",
                    "TLS",
                    "storeTenantCount",
                    3));

    PulseSnapshot pulse = new PulseReader(reader).read();

    assertTrue(pulse.healthy());
    assertEquals(7200L, pulse.uptimeSeconds());
    assertEquals("TLS", pulse.transportProtocol());
    assertEquals(3, pulse.storeTenantCount());
  }

  @Test
  void a_control_plane_that_does_not_answer_is_a_state_rather_than_a_thrown_failure() {
    // A health screen that goes blank when things go wrong is blank exactly when it is needed.
    FakeClusterReader reader = new FakeClusterReader();
    reader.failWith(CliException.unavailable("connection refused"));

    PulseSnapshot pulse = new PulseReader(reader).read();

    assertEquals("UNREACHABLE", pulse.status());
    assertFalse(pulse.healthy());
    assertEquals(Optional.of("connection refused"), pulse.reason());
  }

  @Test
  void the_traffic_rollup_is_read_per_deployment_with_its_owning_tenant() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/health", Map.of("status", "UP"))
            .withList(
                "/metrics",
                List.of(
                    Map.of(
                        "tenantId",
                        "acme",
                        "deploymentName",
                        "checkout-api",
                        "instanceCount",
                        3,
                        "avgRequestRatePerSecond",
                        12.5,
                        "avgErrorRatePerSecond",
                        0.0)));

    PulseSnapshot.DeploymentTraffic row = new PulseReader(reader).read().traffic().getFirst();

    assertEquals(Optional.of("acme"), row.tenantId());
    assertEquals("checkout-api", row.deploymentName());
    assertEquals(3, row.instanceCount());
    assertEquals(12.5, row.requestRatePerSecond());
  }

  @Test
  void a_rollup_the_caller_cannot_read_leaves_health_readable_on_its_own() {
    // The two are gated separately, and "is the control plane up" is the more urgent question.
    FakeClusterReader reader =
        new FakeClusterReader().withObject("/health", Map.of("status", "UP"));

    PulseSnapshot pulse = new PulseReader(reader).read();

    assertTrue(pulse.healthy());
    assertTrue(pulse.traffic().isEmpty());
  }

  @Test
  void a_row_naming_no_deployment_is_dropped_rather_than_charted_as_a_blank_one() {
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/health", Map.of("status", "UP"))
            .withList("/metrics", List.of(Map.of("instanceCount", 2)));

    assertTrue(new PulseReader(reader).read().traffic().isEmpty());
  }

  @Test
  void deployments_sort_busiest_first_and_erroring_ones_are_pulled_out_separately() {
    // A screen with room for a few should spend them on the few that matter.
    FakeClusterReader reader =
        new FakeClusterReader()
            .withObject("/health", Map.of("status", "UP"))
            .withList(
                "/metrics",
                List.of(
                    traffic("quiet-api", 1.0, 0.0),
                    traffic("busy-api", 90.0, 0.0),
                    traffic("broken-api", 5.0, 2.5)));

    PulseSnapshot pulse = new PulseReader(reader).read();

    assertEquals(
        List.of("busy-api", "broken-api", "quiet-api"),
        pulse.busiestFirst().stream()
            .map(PulseSnapshot.DeploymentTraffic::deploymentName)
            .toList());
    assertEquals(
        List.of("broken-api"),
        pulse.erroring().stream().map(PulseSnapshot.DeploymentTraffic::deploymentName).toList());
  }

  private static Map<String, Object> traffic(
      final String name, final double requests, final double errors) {
    return Map.of(
        "deploymentName", name,
        "instanceCount", 1,
        "avgRequestRatePerSecond", requests,
        "avgErrorRatePerSecond", errors);
  }
}
