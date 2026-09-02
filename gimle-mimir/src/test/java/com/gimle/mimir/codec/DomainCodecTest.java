package com.gimle.mimir.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.store.ControllerRevision;
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
                "FIXED_PORT", new VesselEnvValue.PortAllocation(OptionalInt.of(9000)),
                "DATA_DIR", new VesselEnvValue.VolumeMount(1_073_741_824L, ReclaimPolicy.DELETE)),
            List.of(
                new VesselFileMount("conf/app.yaml", "app-config"),
                new VesselFileMount("conf/db.pass", Optional.empty(), Optional.of("db.password"))),
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
  void a_deployment_spec_with_config_map_refs_round_trips() throws Exception {
    DeploymentSpec spec =
        new DeploymentSpec(
            "orders-service",
            new ModuleId("com.gimle.example.orders", Version.parse("1.2.0")),
            "/artifacts/orders-1.2.0.jar",
            3,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.of("acme"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            List.of("app-config", "feature-flags"));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeDeploymentSpec(new DataOutputStream(buffer), spec);
    DeploymentSpec roundTripped =
        DomainCodec.readDeploymentSpec(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(List.of("app-config", "feature-flags"), roundTripped.configMapRefs());
    assertEquals(spec, roundTripped);
  }

  @Test
  void a_controller_revision_embedding_a_deployment_spec_round_trips() throws Exception {
    DeploymentSpec spec =
        new DeploymentSpec(
            "orders-service",
            new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders-1.0.0.jar",
            3,
            PlacementConstraints.NONE);
    ControllerRevision revision =
        new ControllerRevision(
            "Deployment", "orders-service", 1, spec, 1_000L, OptionalInt.empty());

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeControllerRevision(new DataOutputStream(buffer), revision);
    ControllerRevision roundTripped =
        DomainCodec.readControllerRevision(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(revision, roundTripped);
  }

  @Test
  void a_controller_revision_embedding_a_statefulset_spec_round_trips() throws Exception {
    StatefulSetSpec spec =
        new StatefulSetSpec(
            "orders-service",
            new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders-1.0.0.jar",
            3,
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.empty());
    ControllerRevision revision =
        new ControllerRevision(
            "StatefulSet", "orders-service", 1, spec, 1_000L, OptionalInt.empty());

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeControllerRevision(new DataOutputStream(buffer), revision);
    ControllerRevision roundTripped =
        DomainCodec.readControllerRevision(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(revision, roundTripped);
  }

  @Test
  void a_controller_revision_embedding_a_daemonset_spec_round_trips() throws Exception {
    DaemonSetSpec spec =
        new DaemonSetSpec(
            "orders-agent",
            new ModuleId("com.gimle.example.orders-agent", Version.parse("1.0.0")),
            "/artifacts/orders-agent-1.0.0.jar",
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.empty());
    ControllerRevision revision =
        new ControllerRevision("DaemonSet", "orders-agent", 1, spec, 1_000L, OptionalInt.empty());

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeControllerRevision(new DataOutputStream(buffer), revision);
    ControllerRevision roundTripped =
        DomainCodec.readControllerRevision(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(revision, roundTripped);
  }

  @Test
  void a_daemonset_spec_with_tolerate_all_taints_set_round_trips() throws Exception {
    DaemonSetSpec spec =
        new DaemonSetSpec(
            "cluster-log-shipper",
            new ModuleId("com.gimle.example.cluster-log-shipper", Version.parse("1.0.0")),
            "/artifacts/cluster-log-shipper-1.0.0.jar",
            PlacementConstraints.NONE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            true);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeDaemonSetSpec(new DataOutputStream(buffer), spec);
    DaemonSetSpec roundTripped =
        DomainCodec.readDaemonSetSpec(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(spec, roundTripped);
    assertTrue(roundTripped.tolerateAllTaints());
  }

  @Test
  void a_controller_revision_records_which_revision_it_rolled_back_to() throws Exception {
    DeploymentSpec spec =
        new DeploymentSpec(
            "orders-service",
            new ModuleId("com.gimle.example.orders", Version.parse("1.0.0")),
            "/artifacts/orders-1.0.0.jar",
            3,
            PlacementConstraints.NONE);
    ControllerRevision revision =
        new ControllerRevision("Deployment", "orders-service", 2, spec, 2_000L, OptionalInt.of(1));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeControllerRevision(new DataOutputStream(buffer), revision);
    ControllerRevision roundTripped =
        DomainCodec.readControllerRevision(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(OptionalInt.of(1), roundTripped.rollbackOfRevision());
    assertEquals(revision, roundTripped);
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
  void an_instance_observation_with_a_tier_and_resource_limit_round_trips() throws Exception {
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
            Map.of(),
            0L,
            Optional.empty(),
            Optional.empty(),
            Optional.of(IsolationTier.TIER_2),
            Optional.of(new ResourceSpec("256Mi", "1000m")));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeInstanceObservation(new DataOutputStream(buffer), observation);
    InstanceObservation roundTripped =
        DomainCodec.readInstanceObservation(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(observation, roundTripped);
    assertEquals(Optional.of(IsolationTier.TIER_2), roundTripped.isolationTier());
    assertEquals(Optional.of(new ResourceSpec("256Mi", "1000m")), roundTripped.resourceLimit());
  }

  @Test
  void an_instance_observation_with_no_tier_or_limit_round_trips_as_empty() throws Exception {
    // A vessel instance is an OS process with no module descriptor behind it, so it reports
    // neither -- the codec must carry that absence rather than inventing a ceiling.
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

    assertEquals(Optional.empty(), roundTripped.isolationTier());
    assertEquals(Optional.empty(), roundTripped.resourceLimit());
  }

  @Test
  void an_instance_observation_with_a_worker_id_round_trips() throws Exception {
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
            Map.of(),
            0L,
            Optional.of("worker-4821"));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DomainCodec.writeInstanceObservation(new DataOutputStream(buffer), observation);
    InstanceObservation roundTripped =
        DomainCodec.readInstanceObservation(
            new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())));

    assertEquals(observation, roundTripped);
    assertEquals(Optional.of("worker-4821"), roundTripped.workerId());
  }

  @Test
  void an_instance_observation_with_no_worker_id_round_trips_as_empty() throws Exception {
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

    assertEquals(Optional.empty(), roundTripped.workerId());
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
