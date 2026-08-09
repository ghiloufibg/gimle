package com.gimle.mimir.rpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.Role;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.raft.StateMutation;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trips every {@link StoreRpc} variant, mirroring {@code RaftCodecTest}'s structure. Variants
 * carrying a {@code byte[]} field transitively (an {@link Account}'s password hash, a {@link
 * ConfigEntry}'s value) are excluded from the generic equals-based round trip -- record-generated
 * {@code equals()} compares array fields by reference, not content, the same reason {@code
 * RaftCodecTest} field-compares {@code InstallSnapshot} explicitly instead -- and covered by
 * dedicated tests below instead.
 */
class StoreCodecTest {

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

  private static InstanceAssignment assignment() {
    return new InstanceAssignment("greeter", 0, "node-1", MODULE_ID, "/artifacts/greeter.jar");
  }

  private static NodeRegistration nodeRegistration() {
    return new NodeRegistration(
        "node-1", new NodeCapabilities(Set.of(IsolationTier.TIER_1)), Optional.of("node-1:8080"));
  }

  private static Tenant tenant() {
    return new Tenant("tenant-1", new ResourceQuota(1024, 500, 10));
  }

  private static Role role() {
    return new Role(
        "viewer", Set.of(new Permission(ResourceKind.DEPLOYMENT, Verb.READ, Optional.empty())));
  }

  private static RoleBinding roleBinding() {
    return new RoleBinding("binding-1", "user:alice", "viewer");
  }

  private static NodeHeartbeat nodeHeartbeat() {
    return new NodeHeartbeat(
        "node-1",
        new ResourceUsageSnapshot(1024, 512, 4000, 1000),
        List.of(
            new InstanceObservation(
                "greeter", 0, MODULE_ID, "ACTIVE", true, true, 12.5, 0, 100, 2048)));
  }

  private static ObservedHeartbeat observedHeartbeat() {
    return new ObservedHeartbeat(nodeHeartbeat(), Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static InstanceEvent instanceEvent() {
    return new InstanceEvent(
        "evt-1",
        "greeter",
        0,
        InstanceEventKind.TRANSITION_FAILED,
        "transition ACTIVE -> STOPPING failed",
        Optional.of("java.lang.IllegalStateException: boom"),
        1_700_000_000_000L);
  }

  static Stream<StoreRpc> variants() {
    return Stream.of(
        // leader-only writes
        new StoreRpc.Propose(new StateMutation.PutDeployment(deploymentSpec())),
        new StoreRpc.PutHeartbeat(nodeHeartbeat()),
        new StoreRpc.AcquireOrRenewLease("reconciler-leader", "node-a:8080", 15_000L),
        new StoreRpc.ReleaseLease("reconciler-leader", "node-a:8080"),
        new StoreRpc.AddServer("10.0.0.4:7100", "10.0.0.4", 7100, 7200),
        new StoreRpc.RemoveServer("10.0.0.4:7100"),
        // reads
        new StoreRpc.ListAccounts(),
        new StoreRpc.GetTenant("tenant-1"),
        new StoreRpc.GetDeployment("greeter"),
        new StoreRpc.ListDeployments(),
        new StoreRpc.ListAssignmentsFor("greeter"),
        new StoreRpc.IsQuotaViolating("greeter"),
        new StoreRpc.ListAssignments(),
        new StoreRpc.ListNodeRegistrations(),
        new StoreRpc.ListTenants(),
        new StoreRpc.ListConfigEntriesFor("tenant-1"),
        new StoreRpc.ListRoles(),
        new StoreRpc.GetRole("viewer"),
        new StoreRpc.ListRoleBindings(),
        new StoreRpc.GetRoleBinding("binding-1"),
        new StoreRpc.GetAccount("alice"),
        new StoreRpc.GetNodeRegistration("node-1"),
        new StoreRpc.GetEffectiveReplicas("greeter"),
        new StoreRpc.GetRollingIndex("greeter"),
        new StoreRpc.GetNodeHeartbeat("node-1"),
        new StoreRpc.ListInstanceEvents("greeter", 0),
        // responses
        new StoreRpc.Ok(),
        new StoreRpc.NotLeader("node-2:8081"),
        new StoreRpc.NotLeader(""),
        new StoreRpc.LeaseResult(true, "node-a:8080", 1_700_000_000_000L),
        new StoreRpc.BoolResult(true),
        new StoreRpc.IntResult(true, 3),
        new StoreRpc.IntResult(false, 0),
        new StoreRpc.DeploymentResult(true, deploymentSpec()),
        new StoreRpc.DeploymentResult(false, null),
        new StoreRpc.TenantResult(true, tenant()),
        new StoreRpc.TenantResult(false, null),
        new StoreRpc.RoleResult(true, role()),
        new StoreRpc.RoleResult(false, null),
        new StoreRpc.RoleBindingResult(true, roleBinding()),
        new StoreRpc.RoleBindingResult(false, null),
        new StoreRpc.AccountResult(false, null),
        new StoreRpc.NodeRegistrationResult(true, nodeRegistration()),
        new StoreRpc.NodeRegistrationResult(false, null),
        new StoreRpc.HeartbeatResult(true, observedHeartbeat()),
        new StoreRpc.HeartbeatResult(false, null),
        new StoreRpc.AccountListResult(List.of()),
        new StoreRpc.DeploymentListResult(List.of(deploymentSpec())),
        new StoreRpc.AssignmentListResult(List.of(assignment())),
        new StoreRpc.NodeRegistrationListResult(List.of(nodeRegistration())),
        new StoreRpc.TenantListResult(List.of(tenant())),
        new StoreRpc.ConfigEntryListResult(List.of()),
        new StoreRpc.RoleListResult(List.of(role())),
        new StoreRpc.RoleBindingListResult(List.of(roleBinding())),
        new StoreRpc.InstanceEventListResult(List.of(instanceEvent())));
  }

  @ParameterizedTest
  @MethodSource("variants")
  void roundTripsThroughStreams(StoreRpc original) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    StoreCodec.write(buffer, original);
    StoreRpc decoded = StoreCodec.read(new ByteArrayInputStream(buffer.toByteArray()));
    assertEquals(original, decoded);
  }

  @Test
  void round_trips_an_account_result_carrying_a_password_hash() throws IOException {
    Account original = new Account("alice", new byte[] {1, 2, 3, 4, 5});
    StoreRpc.AccountResult originalResult = new StoreRpc.AccountResult(true, original);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    StoreCodec.write(buffer, originalResult);
    StoreRpc.AccountResult decoded =
        (StoreRpc.AccountResult) StoreCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    assertEquals(originalResult.present(), decoded.present());
    assertEquals(original.username(), decoded.value().username());
    assertArrayEquals(original.passwordHash(), decoded.value().passwordHash());
  }

  @Test
  void round_trips_an_account_list_result() throws IOException {
    Account original = new Account("bob", new byte[] {9, 8, 7});
    StoreRpc.AccountListResult originalResult = new StoreRpc.AccountListResult(List.of(original));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    StoreCodec.write(buffer, originalResult);
    StoreRpc.AccountListResult decoded =
        (StoreRpc.AccountListResult)
            StoreCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    assertEquals(1, decoded.values().size());
    assertEquals(original.username(), decoded.values().get(0).username());
    assertArrayEquals(original.passwordHash(), decoded.values().get(0).passwordHash());
  }

  @Test
  void round_trips_a_config_entry_list_result() throws IOException {
    ConfigEntry original = new ConfigEntry("tenant-1", "db.password", new byte[] {5, 6, 7}, true);
    StoreRpc.ConfigEntryListResult originalResult =
        new StoreRpc.ConfigEntryListResult(List.of(original));

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    StoreCodec.write(buffer, originalResult);
    StoreRpc.ConfigEntryListResult decoded =
        (StoreRpc.ConfigEntryListResult)
            StoreCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    assertEquals(1, decoded.values().size());
    ConfigEntry decodedEntry = decoded.values().get(0);
    assertEquals(original.tenantId(), decodedEntry.tenantId());
    assertEquals(original.key(), decodedEntry.key());
    assertEquals(original.encrypted(), decodedEntry.encrypted());
    assertArrayEquals(original.value(), decodedEntry.value());
  }
}
