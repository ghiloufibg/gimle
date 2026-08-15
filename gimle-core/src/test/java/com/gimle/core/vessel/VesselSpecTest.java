package com.gimle.core.vessel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.ResourceSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class VesselSpecTest {

  private static final ResourceSpec REQUEST = new ResourceSpec("256Mi", "100m");
  private static final ResourceSpec LIMIT = new ResourceSpec("512Mi", "500m");

  @Test
  void a_vessel_with_no_probes_and_no_declared_ports_is_valid() {
    VesselSpec vessel =
        new VesselSpec(
            List.of(), List.of(), Map.of(), List.of(), VesselProbes.NONE, REQUEST, LIMIT);
    assertTrue(vessel.probes().liveness().isEmpty());
    assertTrue(vessel.firstDeclaredPortName().isEmpty());
  }

  @Test
  void a_tcp_readiness_probe_requires_at_least_one_declared_port() {
    VesselProbes probes =
        new VesselProbes(Optional.empty(), Optional.of(new VesselProbeSpec.Tcp()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VesselSpec(List.of(), List.of(), Map.of(), List.of(), probes, REQUEST, LIMIT));
  }

  @Test
  void an_http_liveness_probe_requires_at_least_one_declared_port() {
    VesselProbes probes =
        new VesselProbes(Optional.of(new VesselProbeSpec.Http("/health")), Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new VesselSpec(List.of(), List.of(), Map.of(), List.of(), probes, REQUEST, LIMIT));
  }

  @Test
  void a_tcp_probe_with_a_declared_port_is_valid() {
    Map<String, VesselEnvValue> env =
        Map.of("HTTP_PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty()));
    VesselProbes probes =
        new VesselProbes(Optional.empty(), Optional.of(new VesselProbeSpec.Tcp()));
    VesselSpec vessel =
        new VesselSpec(List.of(), List.of(), env, List.of(), probes, REQUEST, LIMIT);
    assertEquals(Optional.of("HTTP_PORT"), vessel.firstDeclaredPortName());
  }

  @Test
  void resource_request_exceeding_limit_is_rejected() {
    ResourceSpec tooBig = new ResourceSpec("1Gi", "100m");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VesselSpec(
                List.of(), List.of(), Map.of(), List.of(), VesselProbes.NONE, tooBig, LIMIT));
  }

  @Test
  void a_fixed_port_allocation_carries_its_own_number() {
    VesselEnvValue.PortAllocation fixed = new VesselEnvValue.PortAllocation(OptionalInt.of(8080));
    assertEquals(8080, fixed.fixedPort().getAsInt());
  }

  @Test
  void a_negative_fixed_port_is_rejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new VesselEnvValue.PortAllocation(OptionalInt.of(-1)));
  }

  @Test
  void a_negative_initial_delay_is_rejected_on_both_probe_kinds() {
    assertThrows(IllegalArgumentException.class, () -> new VesselProbeSpec.Tcp(-1));
    assertThrows(IllegalArgumentException.class, () -> new VesselProbeSpec.Http("/health", -1));
  }
}
