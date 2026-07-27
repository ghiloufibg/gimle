package com.gimle.controlplane.raft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gimle.controlplane.autoscale.AutoscalePolicy;
import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateSnapshot;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RaftCodecTest {

  private static final ModuleId MODULE_ID = new ModuleId("greeter", Version.parse("1.0.0"));

  private static DeploymentSpec deploymentSpec() {
    return new DeploymentSpec(
        "greeter",
        MODULE_ID,
        "/artifacts/greeter.jar",
        3,
        new PlacementConstraints(Optional.of(Set.of("zone-a")), true),
        Optional.of(new AutoscalePolicy(1, 5, 80)),
        Optional.of("tenant-1"));
  }

  private static LogEntry logEntry(long index, StateMutation mutation) {
    return new LogEntry(7L, index, mutation);
  }

  static Stream<RaftRpc> simpleRpcVariants() {
    return Stream.of(
        new RequestVote(3L, "node-1", 10L, 2L),
        new RequestVoteResponse(3L, true),
        new AppendEntriesResponse(3L, false, 9L),
        new InstallSnapshotResponse(4L),
        new AppendEntries(
            5L,
            "node-1",
            2L,
            4L,
            List.of(
                logEntry(3L, new StateMutation.PutDeployment(deploymentSpec())),
                logEntry(4L, new StateMutation.RemoveDeployment("greeter")),
                logEntry(
                    5L,
                    new StateMutation.PutTenant(
                        new Tenant("tenant-1", new ResourceQuota(1024, 500, 10)))),
                logEntry(
                    6L,
                    new StateMutation.PutNodeRegistration(
                        new NodeRegistration(
                            "node-9", new NodeCapabilities(Set.of(IsolationTier.TIER_1)))))),
            2L),
        new AppendEntries(1L, "node-1", 0L, 0L, List.of(), 0L));
  }

  @ParameterizedTest
  @MethodSource("simpleRpcVariants")
  void roundTripsThroughStreams(RaftRpc original) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    RaftCodec.write(buffer, original);
    RaftRpc decoded = RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray()));
    assertEquals(original, decoded);
  }

  @Test
  void round_trips_an_install_snapshot_carrying_arbitrary_bytes() throws IOException {
    byte[] everyByteValue = new byte[256];
    for (int i = 0; i < 256; i++) {
      everyByteValue[i] = (byte) i;
    }
    InstallSnapshot original = new InstallSnapshot(6L, "node-2", 100L, 5L, everyByteValue);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    RaftCodec.write(buffer, original);
    InstallSnapshot decoded =
        (InstallSnapshot) RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    assertEquals(original.term(), decoded.term());
    assertEquals(original.leaderId(), decoded.leaderId());
    assertEquals(original.lastIncludedIndex(), decoded.lastIncludedIndex());
    assertEquals(original.lastIncludedTerm(), decoded.lastIncludedTerm());
    assertArrayEquals(original.snapshotBytes(), decoded.snapshotBytes());
  }

  @Test
  void round_trips_a_config_entry_mutation_carrying_arbitrary_bytes() throws IOException {
    ConfigEntry entry =
        new ConfigEntry("tenant-1", "db.password", new byte[] {1, 2, 3, 0, -1}, true);
    AppendEntries original =
        new AppendEntries(
            2L,
            "node-1",
            0L,
            0L,
            List.of(logEntry(1L, new StateMutation.PutConfigEntry(entry))),
            1L);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    RaftCodec.write(buffer, original);
    AppendEntries decoded =
        (AppendEntries) RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    StateMutation.PutConfigEntry decodedMutation =
        (StateMutation.PutConfigEntry) decoded.entries().get(0).mutation();
    assertEquals(entry.tenantId(), decodedMutation.entry().tenantId());
    assertEquals(entry.key(), decodedMutation.entry().key());
    assertEquals(entry.encrypted(), decodedMutation.entry().encrypted());
    assertArrayEquals(entry.value(), decodedMutation.entry().value());
  }

  @Test
  void reading_past_a_clean_end_of_stream_returns_null() throws IOException {
    assertNull(RaftCodec.read(new ByteArrayInputStream(new byte[0])));
  }

  @Test
  void two_rpcs_written_back_to_back_are_read_independently() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    RaftCodec.write(buffer, new RequestVoteResponse(1L, true));
    RaftCodec.write(buffer, new RequestVoteResponse(2L, false));

    ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
    RequestVoteResponse first = (RequestVoteResponse) RaftCodec.read(in);
    RequestVoteResponse second = (RequestVoteResponse) RaftCodec.read(in);

    assertEquals(1L, first.term());
    assertEquals(2L, second.term());
    assertNull(RaftCodec.read(in));
  }

  @Test
  void encodes_and_decodes_a_log_entry_standalone() {
    LogEntry original = logEntry(42L, new StateMutation.RemoveTenant("tenant-9"));
    byte[] bytes = RaftCodec.encodeLogEntry(original);
    LogEntry decoded = RaftCodec.decodeLogEntry(bytes);
    assertEquals(original, decoded);
  }

  @Test
  void round_trips_a_state_snapshot() {
    StateSnapshot snapshot =
        new StateSnapshot(
            List.of(deploymentSpec()),
            List.of(
                new InstanceAssignment(
                    "greeter", 0, "node-1", MODULE_ID, "/artifacts/greeter.jar")),
            List.of(
                new NodeRegistration(
                    "node-1",
                    new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)))),
            Map.of("greeter", 1),
            Map.of("greeter", 3),
            List.of(new Tenant("tenant-1", new ResourceQuota(2048, 1000, 20))),
            Set.of("greeter"),
            List.of(new ConfigEntry("tenant-1", "api.key", new byte[] {5, 6, 7}, false)));

    byte[] bytes = RaftCodec.encodeSnapshot(snapshot);
    StateSnapshot decoded = RaftCodec.decodeSnapshot(bytes);

    assertEquals(snapshot.deployments(), decoded.deployments());
    assertEquals(snapshot.assignments(), decoded.assignments());
    assertEquals(snapshot.nodeRegistrations(), decoded.nodeRegistrations());
    assertEquals(snapshot.rollingIndices(), decoded.rollingIndices());
    assertEquals(snapshot.effectiveReplicas(), decoded.effectiveReplicas());
    assertEquals(snapshot.tenants(), decoded.tenants());
    assertEquals(snapshot.quotaViolatingDeployments(), decoded.quotaViolatingDeployments());
    assertEquals(1, decoded.configEntries().size());
    assertEquals(
        snapshot.configEntries().get(0).tenantId(), decoded.configEntries().get(0).tenantId());
    assertEquals(snapshot.configEntries().get(0).key(), decoded.configEntries().get(0).key());
    assertArrayEquals(
        snapshot.configEntries().get(0).value(), decoded.configEntries().get(0).value());
  }
}
