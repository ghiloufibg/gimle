package com.gimle.mimir.raft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.galdr.KindNames;
import com.gimle.mimir.galdr.KindScope;
import com.gimle.mimir.galdr.PrintColumn;
import com.gimle.mimir.galdr.SchemaField;
import com.gimle.mimir.galdr.SchemaModel;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StateStore;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
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
        // All three multi-signal fields present, not just the CPU-only 3-arg constructor: a real
        // bug (found via a real-cluster QA session) let DomainCodec silently drop these three on
        // every write/read, but the generic round-trip equality check below never caught it,
        // because "empty" round-trips correctly whether or not the codec ever touches these
        // fields at all. Real, present values here make that gap impossible to miss again.
        Optional.of(
            new AutoscalePolicy(
                1, 5, 80, OptionalDouble.of(50.0), OptionalDouble.of(5.0), OptionalInt.of(10))),
        Optional.of("tenant-1"),
        Optional.of("b".repeat(64)));
  }

  private static LogEntry logEntry(long index, RaftLogPayload payload) {
    return new LogEntry(7L, index, payload);
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
                logEntry(3L, new StateMutation.PutDeployment(deploymentSpec(), 0)),
                logEntry(4L, new StateMutation.RemoveDeployment(Optional.empty(), "greeter", 1)),
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
  void round_trips_through_streams(RaftRpc original) throws IOException {
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
    InstallSnapshot original =
        new InstallSnapshot(6L, "node-2", 100L, 5L, 128L, everyByteValue, true);

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    RaftCodec.write(buffer, original);
    InstallSnapshot decoded =
        (InstallSnapshot) RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray()));

    assertEquals(original.term(), decoded.term());
    assertEquals(original.leaderId(), decoded.leaderId());
    assertEquals(original.lastIncludedIndex(), decoded.lastIncludedIndex());
    assertEquals(original.lastIncludedTerm(), decoded.lastIncludedTerm());
    assertEquals(original.offset(), decoded.offset());
    assertEquals(original.done(), decoded.done());
    assertArrayEquals(original.data(), decoded.data());
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
        (StateMutation.PutConfigEntry) decoded.entries().get(0).payload();
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
  void rejects_an_oversized_length_prefix_before_allocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeInt(Integer.MAX_VALUE);
    assertThrows(
        GimleCodecException.class,
        () -> RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray())));
  }

  @Test
  void rejects_a_negative_length_prefix_before_allocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeInt(-1);
    assertThrows(
        GimleCodecException.class,
        () -> RaftCodec.read(new ByteArrayInputStream(buffer.toByteArray())));
  }

  @Test
  void rejects_a_forged_huge_entry_count_without_preallocating() throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream bodyOut = new DataOutputStream(body);
    bodyOut.writeByte(1); // version
    bodyOut.writeByte(2); // TAG_APPEND_ENTRIES
    bodyOut.writeLong(1L); // term
    bodyOut.writeUTF("node-1"); // leaderId
    bodyOut.writeLong(0L); // prevLogIndex
    bodyOut.writeLong(0L); // prevLogTerm
    bodyOut.writeInt(Integer.MAX_VALUE - 8); // forged entry count
    // No entries or trailing fields follow -- a pre-sized ArrayList would have allocated an
    // ~8GB backing array before ever discovering that.
    byte[] bodyBytes = body.toByteArray();

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    new DataOutputStream(frame).writeInt(bodyBytes.length);
    frame.write(bodyBytes);
    byte[] frameBytes = frame.toByteArray();

    assertThrows(
        UncheckedIOException.class, () -> RaftCodec.read(new ByteArrayInputStream(frameBytes)));
  }

  @Test
  void rejects_an_unrecognized_rpc_version_before_decoding_the_tag() throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream bodyOut = new DataOutputStream(body);
    bodyOut.writeByte(99); // an unrecognized future version
    // Deliberately garbage after the version byte -- if the version check didn't happen first, a
    // naive decoder might still try (and fail differently, or worse, succeed with garbage) to
    // interpret this as a tag/fields. The version check must reject before any of that runs.
    bodyOut.writeByte(0);
    bodyOut.writeLong(0L);
    byte[] bodyBytes = body.toByteArray();

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    new DataOutputStream(frame).writeInt(bodyBytes.length);
    frame.write(bodyBytes);
    byte[] frameBytes = frame.toByteArray();

    GimleCodecException thrown =
        assertThrows(
            GimleCodecException.class, () -> RaftCodec.read(new ByteArrayInputStream(frameBytes)));
    assertTrue(
        thrown.getMessage().contains("99") && thrown.getMessage().contains("1"),
        "expected the message to name both the declared (99) and max supported (1) versions, got: "
            + thrown.getMessage());
  }

  @Test
  void rejects_an_unrecognized_snapshot_version() {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream bodyOut = new DataOutputStream(body);
    try {
      bodyOut.writeByte(99); // an unrecognized future version
      bodyOut.writeInt(0); // deliberately garbage after the version byte, same rationale as above
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    byte[] bytes = body.toByteArray();

    assertThrows(GimleCodecException.class, () -> RaftCodec.decodeSnapshot(bytes));
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
  void round_trips_a_put_and_remove_alert_rule_mutation() {
    AlertRuleSpec spec =
        new AlertRuleSpec(
            "greeter-error-rate",
            Optional.of("tenant-1"),
            "greeter",
            AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
            AlertRuleSpec.Comparator.GREATER_THAN,
            5.0,
            "https://hooks.example.com/greeter-alerts",
            false);

    LogEntry putEntry = logEntry(1L, new StateMutation.PutAlertRule(spec));
    LogEntry decodedPut = RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(putEntry));
    assertEquals(putEntry, decodedPut);
    assertEquals(spec, ((StateMutation.PutAlertRule) decodedPut.payload()).spec());

    LogEntry removeEntry =
        logEntry(2L, new StateMutation.RemoveAlertRule(Optional.of("tenant-1"), spec.name()));
    LogEntry decodedRemove = RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(removeEntry));
    assertEquals(removeEntry, decodedRemove);
  }

  @Test
  void round_trips_a_weighted_autoscale_policy_with_every_weight_present() {
    // Same historical-bug shape as deploymentSpec()'s own comment above: a WEIGHTED-mode policy
    // with every weight populated makes it impossible for DomainCodec to silently drop
    // combinationMode/the four weight fields the way it once dropped the three multi-signal
    // targets, since "empty" alone would round-trip correctly whether or not the codec touches
    // these fields at all.
    DeploymentSpec spec =
        new DeploymentSpec(
            "greeter",
            MODULE_ID,
            "/artifacts/greeter.jar",
            3,
            PlacementConstraints.NONE,
            Optional.of(
                new AutoscalePolicy(
                    1,
                    5,
                    80,
                    OptionalDouble.of(50.0),
                    OptionalDouble.of(5.0),
                    OptionalInt.of(10),
                    AutoscalePolicy.CombinationMode.WEIGHTED,
                    OptionalDouble.of(1.0),
                    OptionalDouble.of(3.0),
                    OptionalDouble.of(2.0),
                    OptionalDouble.of(1.5))),
            Optional.empty(),
            Optional.empty());
    LogEntry original = logEntry(1L, new StateMutation.PutDeployment(spec, 0));

    byte[] bytes = RaftCodec.encodeLogEntry(original);
    LogEntry decoded = RaftCodec.decodeLogEntry(bytes);

    assertEquals(original, decoded);
  }

  @Test
  void round_trips_a_state_snapshot() {
    StateSnapshot snapshot =
        new StateSnapshot(
            List.of(deploymentSpec()),
            Map.of("greeter", 3L),
            List.of(
                new InstanceAssignment(
                    "greeter", 0, "node-1", MODULE_ID, "/artifacts/greeter.jar")),
            List.of(),
            List.of(),
            Map.of(),
            List.of(
                new JobRunSummary(
                    "greeter-job", 2, "node-1", "exhausted backoffLimit of 3 attempts")),
            List.of(),
            Map.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(
                new NodeRegistration(
                    "node-1",
                    new NodeCapabilities(Set.of(IsolationTier.TIER_1, IsolationTier.TIER_2)))),
            Map.of("greeter", Set.of(1)),
            Map.of("greeter", Map.of(3, 1)),
            Map.of("greeter", 3),
            List.of(new Tenant("tenant-1", new ResourceQuota(2048, 1000, 20))),
            Set.of("greeter"),
            List.of(new ConfigEntry("tenant-1", "api.key", new byte[] {5, 6, 7}, false)),
            List.of(
                new com.gimle.core.authz.Role(
                    "viewer",
                    Set.of(
                        Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.READ),
                        Permission.scoped(ResourceKind.CONFIG, Verb.READ, "tenant-1")))),
            List.of(new RoleBinding("b1", RoleBinding.groupSubject("gimle:operators"), "viewer")),
            List.of(new Account("admin", new byte[] {9, 8, 7, 6})),
            List.of(new ReconcilerInstanceState("greeter", 0, 2, 100L, 200L, true, false, -1L)),
            Set.of("node-1"),
            Map.of(
                "greeter",
                List.of(
                    new InstanceEvent(
                        "evt-1", "greeter", 0, InstanceEventKind.ACTIVE, "module active", 1_000L))),
            List.of(
                new AuditEvent(
                    "audit-1",
                    "alice",
                    Set.of("gimle:operators"),
                    "DEPLOYMENT",
                    "WRITE",
                    Optional.of("tenant-1"),
                    Optional.of("greeter"),
                    true,
                    1_500L)),
            List.of(
                new ServiceSpec("greeter", Optional.of("tenant-1"), Set.of("greeter"), 8080, 9090)),
            List.of(
                new NetworkPolicySpec(
                    "greeter-policy",
                    "tenant-1",
                    Optional.of(Set.of("greeter")),
                    Set.of("tenant-2"))),
            List.of(
                new ControllerRevision(
                    "Deployment", "greeter", 1, deploymentSpec(), 1_000L, OptionalInt.empty())),
            List.of(),
            Map.of(),
            Set.of("0a1b2c"),
            List.of(
                new WorkloadTokenRecord(
                    "orders#node-1", "ab12cd", Optional.of("tenant-1"), "orders", 9_999L)),
            Map.of("node-1", Set.of("tenant-1", "tenant-2")),
            List.of(
                new KindDefinitionSpec(
                    "custom.Greeting",
                    KindScope.TENANT,
                    "a greeting this cluster should keep saying",
                    new KindNames(Optional.of("greetings"), List.of("gr")),
                    new SchemaModel(
                        List.of(
                            new SchemaField.StringField(
                                "message", true, Optional.empty(), OptionalInt.empty()))),
                    List.of(new PrintColumn("MESSAGE", "spec.message")),
                    2L)),
            List.of(
                new CustomResource(
                    "custom.Greeting",
                    "hello-world",
                    Optional.of("tenant-1"),
                    new byte[] {123, 125},
                    new byte[0],
                    1L)),
            List.of(
                new WorkloadHealthState(
                    "StatefulSet",
                    "orders",
                    "0",
                    2,
                    100L,
                    200L,
                    true,
                    false,
                    WorkloadHealthState.ABSENT,
                    Optional.empty())),
            Map.of("alice", 42_000L),
            List.of(
                new AlertRuleSpec(
                    "greeter-error-rate",
                    Optional.of("tenant-1"),
                    "greeter",
                    AlertRuleSpec.Metric.ERROR_RATE_PER_SECOND,
                    AlertRuleSpec.Comparator.GREATER_THAN,
                    5.0,
                    "https://hooks.example.com/greeter-alerts")));

    byte[] bytes = RaftCodec.encodeSnapshot(snapshot);
    StateSnapshot decoded = RaftCodec.decodeSnapshot(bytes);

    assertEquals(snapshot.deployments(), decoded.deployments());
    assertEquals(snapshot.assignments(), decoded.assignments());
    assertEquals(snapshot.nodeRegistrations(), decoded.nodeRegistrations());
    assertEquals(snapshot.rollingIndices(), decoded.rollingIndices());
    assertEquals(snapshot.surgeIndices(), decoded.surgeIndices());
    assertEquals(snapshot.effectiveReplicas(), decoded.effectiveReplicas());
    assertEquals(snapshot.tenants(), decoded.tenants());
    assertEquals(snapshot.quotaViolatingDeployments(), decoded.quotaViolatingDeployments());
    assertEquals(snapshot.workloadHealthStates(), decoded.workloadHealthStates());
    assertEquals(1, decoded.configEntries().size());
    assertEquals(
        snapshot.configEntries().get(0).tenantId(), decoded.configEntries().get(0).tenantId());
    assertEquals(snapshot.configEntries().get(0).key(), decoded.configEntries().get(0).key());
    assertArrayEquals(
        snapshot.configEntries().get(0).value(), decoded.configEntries().get(0).value());
    assertEquals(snapshot.roles(), decoded.roles());
    assertEquals(snapshot.roleBindings(), decoded.roleBindings());
    assertEquals(1, decoded.accounts().size());
    assertEquals(snapshot.accounts().get(0).username(), decoded.accounts().get(0).username());
    assertArrayEquals(
        snapshot.accounts().get(0).passwordHash(), decoded.accounts().get(0).passwordHash());
    assertEquals(snapshot.reconcilerInstanceStates(), decoded.reconcilerInstanceStates());
    assertEquals(snapshot.cordonedNodes(), decoded.cordonedNodes());
    assertEquals(snapshot.alertRules(), decoded.alertRules());
    assertEquals(snapshot.instanceEvents(), decoded.instanceEvents());
    assertEquals(snapshot.auditEvents(), decoded.auditEvents());
    assertEquals(snapshot.services(), decoded.services());
    assertEquals(snapshot.networkPolicies(), decoded.networkPolicies());
    assertEquals(snapshot.controllerRevisions(), decoded.controllerRevisions());
    assertEquals(snapshot.revokedCertificateSerials(), decoded.revokedCertificateSerials());
    assertEquals(snapshot.workloadTokens(), decoded.workloadTokens());
    assertEquals(snapshot.jobRunSummaries(), decoded.jobRunSummaries());
    assertEquals(snapshot.nodeTaints(), decoded.nodeTaints());
    assertEquals(snapshot.kindDefinitions(), decoded.kindDefinitions());
    assertEquals(1, decoded.customResources().size());
    CustomResource decodedResource = decoded.customResources().get(0);
    CustomResource originalResource = snapshot.customResources().get(0);
    assertEquals(originalResource.kindName(), decodedResource.kindName());
    assertEquals(originalResource.name(), decodedResource.name());
    assertEquals(originalResource.tenantId(), decodedResource.tenantId());
    assertEquals(originalResource.generation(), decodedResource.generation());
    assertArrayEquals(originalResource.specJson(), decodedResource.specJson());
    assertArrayEquals(originalResource.statusJson(), decodedResource.statusJson());
    assertEquals(
        snapshot.sessionRevokedBeforeEpochMilli(), decoded.sessionRevokedBeforeEpochMilli());
  }

  @Test
  void round_trips_a_restore_snapshot_mutation_through_a_log_entry() {
    StateStore seedStore = new StateStore();
    seedStore.putDeployment(deploymentSpec());
    StateSnapshot snapshot = seedStore.snapshot();
    LogEntry entry = logEntry(1L, new StateMutation.RestoreSnapshot(snapshot));
    LogEntry decoded = RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(entry));
    StateMutation.RestoreSnapshot decodedMutation =
        (StateMutation.RestoreSnapshot) decoded.payload();
    assertEquals(snapshot.deployments(), decodedMutation.snapshot().deployments());
    assertEquals(
        snapshot.deploymentGenerations(), decodedMutation.snapshot().deploymentGenerations());
  }

  @Test
  void round_trips_a_batch_mutation_through_a_log_entry() {
    StateMutation.Batch batch =
        new StateMutation.Batch(
            List.of(
                new StateMutation.RemoveDeployment(Optional.empty(), "orders-service", 0),
                new StateMutation.RemoveAssignment(Optional.empty(), "orders-service", 0),
                new StateMutation.AddRollingIndex(Optional.empty(), "orders-service", 1)));
    LogEntry entry = logEntry(1L, batch);
    assertEquals(entry, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(entry)));
  }

  @Test
  void round_trips_role_rolebinding_and_account_mutations_through_a_log_entry() {
    com.gimle.core.authz.Role role =
        new com.gimle.core.authz.Role(
            "cluster-admin", Set.of(Permission.unscoped(ResourceKind.DEPLOYMENT, Verb.WRITE)));
    LogEntry putRole = logEntry(1L, new StateMutation.PutRole(role));
    assertEquals(putRole, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(putRole)));

    LogEntry removeRole = logEntry(2L, new StateMutation.RemoveRole("cluster-admin"));
    assertEquals(removeRole, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(removeRole)));

    RoleBinding binding = new RoleBinding("b1", RoleBinding.userSubject("alice"), "cluster-admin");
    LogEntry putBinding = logEntry(3L, new StateMutation.PutRoleBinding(binding));
    assertEquals(putBinding, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(putBinding)));

    LogEntry removeBinding = logEntry(4L, new StateMutation.RemoveRoleBinding("b1"));
    assertEquals(removeBinding, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(removeBinding)));

    LogEntry removeAccount = logEntry(5L, new StateMutation.RemoveAccount("admin"));
    assertEquals(removeAccount, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(removeAccount)));
  }

  @Test
  void round_trips_an_append_instance_event_mutation_with_and_without_a_cause_summary() {
    InstanceEvent withoutCause =
        new InstanceEvent("evt-1", "greeter", 0, InstanceEventKind.ACTIVE, "module active", 1_000L);
    LogEntry active =
        logEntry(1L, new StateMutation.AppendInstanceEvent(Optional.empty(), withoutCause));
    assertEquals(active, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(active)));

    InstanceEvent withCause =
        new InstanceEvent(
            "evt-2",
            "greeter",
            0,
            InstanceEventKind.TRANSITION_FAILED,
            "transition ACTIVE -> STOPPING failed",
            Optional.of("java.lang.IllegalStateException: boom"),
            2_000L);
    LogEntry failed =
        logEntry(2L, new StateMutation.AppendInstanceEvent(Optional.empty(), withCause));
    assertEquals(failed, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(failed)));
  }

  @Test
  void round_trips_an_append_audit_event_mutation_allowed_and_denied_with_and_without_scope() {
    AuditEvent allowedWithScope =
        new AuditEvent(
            "audit-1",
            "alice",
            Set.of("gimle:operators"),
            "DEPLOYMENT",
            "WRITE",
            Optional.of("tenant-1"),
            Optional.of("greeter"),
            true,
            1_000L);
    LogEntry allowed = logEntry(1L, new StateMutation.AppendAuditEvent(allowedWithScope));
    assertEquals(allowed, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(allowed)));

    AuditEvent deniedUnscoped =
        new AuditEvent(
            "audit-2",
            "mallory",
            Set.of(),
            "TENANT",
            "DELETE",
            Optional.empty(),
            Optional.empty(),
            false,
            2_000L);
    LogEntry denied = logEntry(2L, new StateMutation.AppendAuditEvent(deniedUnscoped));
    assertEquals(denied, RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(denied)));
  }

  @Test
  void round_trips_a_put_account_mutation_carrying_arbitrary_password_hash_bytes() {
    Account account = new Account("admin", new byte[] {1, 2, 3, 0, -1});
    LogEntry original = logEntry(1L, new StateMutation.PutAccount(account));

    LogEntry decoded = RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(original));

    StateMutation.PutAccount decodedMutation = (StateMutation.PutAccount) decoded.payload();
    assertEquals(account.username(), decodedMutation.account().username());
    assertArrayEquals(account.passwordHash(), decodedMutation.account().passwordHash());
  }

  @Test
  void round_trips_a_log_entry_carrying_a_membership_change() {
    Map<String, PeerAddress> peers =
        Map.of(
            "node-2", new PeerAddress("10.0.0.2", 7100, 7200),
            "node-3", new PeerAddress("10.0.0.3", 7100, 7200));
    Set<String> learners = Set.of("node-3");
    LogEntry original = logEntry(9L, new MembershipChange(peers, learners));

    LogEntry decoded = RaftCodec.decodeLogEntry(RaftCodec.encodeLogEntry(original));

    assertEquals(original, decoded);
    assertEquals(peers, ((MembershipChange) decoded.payload()).peers());
    assertEquals(learners, ((MembershipChange) decoded.payload()).learners());
  }
}
