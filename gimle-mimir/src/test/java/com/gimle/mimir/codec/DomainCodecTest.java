package com.gimle.mimir.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the wire shapes this task added to {@link DomainCodec}: a {@link VesselSpec} block
 * (every field, every {@link VesselEnvValue}/{@link VesselProbeSpec} variant) and {@link
 * InstanceObservation}'s new declared-port map.
 */
class DomainCodecTest {

  @Test
  void a_vessel_spec_round_trips_through_the_wire() throws Exception {
    VesselSpec vessel =
        new VesselSpec(
            List.of("--flag1", "--flag2"),
            List.of("-XX:+UseZGC"),
            Map.of(
                "LOG_LEVEL", new VesselEnvValue.Literal("INFO"),
                "DB_PASSWORD", new VesselEnvValue.SecretRef("db.password"),
                "HTTP_PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty()),
                "FIXED_PORT", new VesselEnvValue.PortAllocation(OptionalInt.of(9000))),
            List.of(new VesselFileMount("conf/app.yaml", "app-config")),
            new VesselProbes(
                Optional.of(new VesselProbeSpec.Http("/health", 20)),
                Optional.of(new VesselProbeSpec.Tcp(5))),
            new ResourceSpec("256Mi", "100m"),
            new ResourceSpec("512Mi", "500m"));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeOptionalVesselSpec(new DataOutputStream(buffer), Optional.of(vessel));
    Optional<VesselSpec> roundTripped =
        DomainCodec.readOptionalVesselSpec(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(vessel, roundTripped.orElseThrow());
  }

  @Test
  void an_absent_vessel_spec_round_trips_as_empty() throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeOptionalVesselSpec(new DataOutputStream(buffer), Optional.empty());
    Optional<VesselSpec> roundTripped =
        DomainCodec.readOptionalVesselSpec(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(Optional.empty(), roundTripped);
  }

  @Test
  void a_deployment_spec_with_a_vessel_round_trips() throws Exception {
    VesselSpec vessel =
        new VesselSpec(
            List.of(),
            List.of(),
            Map.of("HTTP_PORT", new VesselEnvValue.PortAllocation(OptionalInt.empty())),
            List.of(),
            VesselProbes.NONE,
            new ResourceSpec("256Mi", "100m"),
            new ResourceSpec("512Mi", "500m"));
    DeploymentSpec spec =
        new DeploymentSpec(
            "billing-api",
            new ModuleId("com.acme.billing-api", Version.parse("2.3.1")),
            "/artifacts/billing-2.3.1.jar",
            3,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("acme"),
            Optional.empty(),
            Optional.empty(),
            Optional.of(vessel));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeDeploymentSpec(new DataOutputStream(buffer), spec);
    DeploymentSpec roundTripped =
        DomainCodec.readDeploymentSpec(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(spec, roundTripped);
  }

  @Test
  void an_instance_observation_with_ports_round_trips() throws Exception {
    InstanceObservation observation =
        new InstanceObservation(
            "billing-api",
            0,
            new ModuleId("com.acme.billing-api", Version.parse("2.3.1")),
            "ACTIVE",
            true,
            true,
            0.0,
            0,
            0L,
            0L,
            0.0,
            Map.of("HTTP_PORT", 54321));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeInstanceObservation(new DataOutputStream(buffer), observation);
    InstanceObservation roundTripped =
        DomainCodec.readInstanceObservation(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(observation, roundTripped);
    assertEquals(54321, roundTripped.ports().get("HTTP_PORT"));
  }

  @Test
  void an_instance_observation_with_no_ports_round_trips_as_empty() throws Exception {
    InstanceObservation observation =
        new InstanceObservation(
            "greeter",
            0,
            new ModuleId("com.acme.greeter", Version.parse("1.0.0")),
            "ACTIVE",
            true,
            true);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeInstanceObservation(new DataOutputStream(buffer), observation);
    InstanceObservation roundTripped =
        DomainCodec.readInstanceObservation(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(Map.of(), roundTripped.ports());
  }
}
