package com.gimle.controlplane.raft;

import com.gimle.controlplane.autoscale.AutoscalePolicy;
import com.gimle.controlplane.manifest.DeploymentSpec;
import com.gimle.controlplane.manifest.PlacementConstraints;
import com.gimle.controlplane.store.InstanceAssignment;
import com.gimle.controlplane.store.StateSnapshot;
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
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Encodes/decodes a {@link RaftRpc} the same way {@code gimle-fabric}'s {@code FabricCodec} encodes
 * a {@code FabricFrame}: a 4-byte big-endian length prefix, a one-byte type tag, then {@link
 * DataOutputStream} primitive fields, with every {@code byte[]} field itself separately
 * length-prefixed. A {@link LogEntry} carrying a {@link StateMutation} is exactly the same kind of
 * arbitrary-byte payload {@code InvokeRequest}'s {@code serializedArgs} already is, so this reuses
 * that framing shape rather than inventing a third one.
 *
 * <p>Also encodes/decodes a {@link StateSnapshot} as a standalone byte array -- the payload an
 * {@link InstallSnapshot} RPC carries, and what {@link RaftLog} persists to disk after a local
 * compaction.
 */
public final class RaftCodec {

  private static final byte TAG_REQUEST_VOTE = 0;
  private static final byte TAG_REQUEST_VOTE_RESPONSE = 1;
  private static final byte TAG_APPEND_ENTRIES = 2;
  private static final byte TAG_APPEND_ENTRIES_RESPONSE = 3;
  private static final byte TAG_INSTALL_SNAPSHOT = 4;
  private static final byte TAG_INSTALL_SNAPSHOT_RESPONSE = 5;

  private static final byte MUT_PUT_DEPLOYMENT = 0;
  private static final byte MUT_REMOVE_DEPLOYMENT = 1;
  private static final byte MUT_PUT_ASSIGNMENT = 2;
  private static final byte MUT_REMOVE_ASSIGNMENT = 3;
  private static final byte MUT_PUT_ROLLING_INDEX = 4;
  private static final byte MUT_CLEAR_ROLLING_INDEX = 5;
  private static final byte MUT_PUT_EFFECTIVE_REPLICAS = 6;
  private static final byte MUT_PUT_NODE_REGISTRATION = 7;
  private static final byte MUT_PUT_TENANT = 8;
  private static final byte MUT_REMOVE_TENANT = 9;
  private static final byte MUT_PUT_QUOTA_VIOLATION = 10;
  private static final byte MUT_PUT_CONFIG_ENTRY = 11;
  private static final byte MUT_REMOVE_CONFIG_ENTRY = 12;
  private static final byte MUT_PUT_ROLE = 13;
  private static final byte MUT_REMOVE_ROLE = 14;
  private static final byte MUT_PUT_ROLE_BINDING = 15;
  private static final byte MUT_REMOVE_ROLE_BINDING = 16;
  private static final byte MUT_PUT_ACCOUNT = 17;
  private static final byte MUT_REMOVE_ACCOUNT = 18;

  /**
   * Generous upper bound for any single length-prefixed frame or byte-array field this codec ever
   * produces (a {@link StateSnapshot} is the largest payload in practice) -- far below what a
   * corrupted or adversarial peer could otherwise force this reader to allocate.
   */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private RaftCodec() {}

  private static void checkFrameLength(int length) {
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
  }

  // ---- top-level RaftRpc framing (length-prefixed, matching FabricCodec) ----

  public static void write(OutputStream out, RaftRpc rpc) throws IOException {
    byte[] body = encodeRpcBody(rpc);
    DataOutputStream data = new DataOutputStream(out);
    data.writeInt(body.length);
    data.write(body);
    data.flush();
  }

  /**
   * Returns {@code null} at a clean end-of-stream, matching {@code FabricCodec.read}'s convention.
   */
  public static RaftRpc read(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    int length;
    try {
      length = data.readInt();
    } catch (EOFException e) {
      return null;
    }
    checkFrameLength(length);
    byte[] body = new byte[length];
    data.readFully(body);
    return decodeRpcBody(body);
  }

  private static byte[] encodeRpcBody(RaftRpc rpc) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      switch (rpc) {
        case RequestVote v -> {
          out.writeByte(TAG_REQUEST_VOTE);
          out.writeLong(v.term());
          out.writeUTF(v.candidateId());
          out.writeLong(v.lastLogIndex());
          out.writeLong(v.lastLogTerm());
        }
        case RequestVoteResponse v -> {
          out.writeByte(TAG_REQUEST_VOTE_RESPONSE);
          out.writeLong(v.term());
          out.writeBoolean(v.voteGranted());
        }
        case AppendEntries v -> {
          out.writeByte(TAG_APPEND_ENTRIES);
          out.writeLong(v.term());
          out.writeUTF(v.leaderId());
          out.writeLong(v.prevLogIndex());
          out.writeLong(v.prevLogTerm());
          out.writeInt(v.entries().size());
          for (LogEntry entry : v.entries()) {
            writeLogEntry(out, entry);
          }
          out.writeLong(v.leaderCommitIndex());
        }
        case AppendEntriesResponse v -> {
          out.writeByte(TAG_APPEND_ENTRIES_RESPONSE);
          out.writeLong(v.term());
          out.writeBoolean(v.success());
          out.writeLong(v.matchIndex());
        }
        case InstallSnapshot v -> {
          out.writeByte(TAG_INSTALL_SNAPSHOT);
          out.writeLong(v.term());
          out.writeUTF(v.leaderId());
          out.writeLong(v.lastIncludedIndex());
          out.writeLong(v.lastIncludedTerm());
          writeBytes(out, v.snapshotBytes());
        }
        case InstallSnapshotResponse v -> {
          out.writeByte(TAG_INSTALL_SNAPSHOT_RESPONSE);
          out.writeLong(v.term());
        }
      }
    } catch (IOException e) {
      // ByteArrayOutputStream never throws IOException in practice; DataOutputStream#write* just
      // declares it (same rationale as FabricCodec.encodeBody).
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static RaftRpc decodeRpcBody(byte[] body) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      byte tag = in.readByte();
      return switch (tag) {
        case TAG_REQUEST_VOTE ->
            new RequestVote(in.readLong(), in.readUTF(), in.readLong(), in.readLong());
        case TAG_REQUEST_VOTE_RESPONSE -> new RequestVoteResponse(in.readLong(), in.readBoolean());
        case TAG_APPEND_ENTRIES -> {
          long term = in.readLong();
          String leaderId = in.readUTF();
          long prevLogIndex = in.readLong();
          long prevLogTerm = in.readLong();
          int entryCount = in.readInt();
          List<LogEntry> entries = new ArrayList<>();
          for (int i = 0; i < entryCount; i++) {
            entries.add(readLogEntry(in));
          }
          long leaderCommitIndex = in.readLong();
          yield new AppendEntries(
              term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommitIndex);
        }
        case TAG_APPEND_ENTRIES_RESPONSE ->
            new AppendEntriesResponse(in.readLong(), in.readBoolean(), in.readLong());
        case TAG_INSTALL_SNAPSHOT -> {
          long term = in.readLong();
          String leaderId = in.readUTF();
          long lastIncludedIndex = in.readLong();
          long lastIncludedTerm = in.readLong();
          byte[] snapshotBytes = readBytes(in);
          yield new InstallSnapshot(
              term, leaderId, lastIncludedIndex, lastIncludedTerm, snapshotBytes);
        }
        case TAG_INSTALL_SNAPSHOT_RESPONSE -> new InstallSnapshotResponse(in.readLong());
        default -> throw new IllegalArgumentException("unknown Raft RPC tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- LogEntry / StateMutation ----

  static void writeLogEntry(DataOutputStream out, LogEntry entry) throws IOException {
    out.writeLong(entry.term());
    out.writeLong(entry.index());
    writeStateMutation(out, entry.mutation());
  }

  static LogEntry readLogEntry(DataInputStream in) throws IOException {
    long term = in.readLong();
    long index = in.readLong();
    StateMutation mutation = readStateMutation(in);
    return new LogEntry(term, index, mutation);
  }

  /** Encodes a single {@link LogEntry} standalone -- what {@code RaftLog} persists per index. */
  public static byte[] encodeLogEntry(LogEntry entry) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      writeLogEntry(new DataOutputStream(buffer), entry);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  public static LogEntry decodeLogEntry(byte[] bytes) {
    try {
      return readLogEntry(new DataInputStream(new ByteArrayInputStream(bytes)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeStateMutation(DataOutputStream out, StateMutation mutation)
      throws IOException {
    switch (mutation) {
      case StateMutation.PutDeployment m -> {
        out.writeByte(MUT_PUT_DEPLOYMENT);
        writeDeploymentSpec(out, m.spec());
      }
      case StateMutation.RemoveDeployment m -> {
        out.writeByte(MUT_REMOVE_DEPLOYMENT);
        out.writeUTF(m.name());
      }
      case StateMutation.PutAssignment m -> {
        out.writeByte(MUT_PUT_ASSIGNMENT);
        writeInstanceAssignment(out, m.assignment());
      }
      case StateMutation.RemoveAssignment m -> {
        out.writeByte(MUT_REMOVE_ASSIGNMENT);
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.PutRollingIndex m -> {
        out.writeByte(MUT_PUT_ROLLING_INDEX);
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.ClearRollingIndex m -> {
        out.writeByte(MUT_CLEAR_ROLLING_INDEX);
        out.writeUTF(m.deploymentName());
      }
      case StateMutation.PutEffectiveReplicas m -> {
        out.writeByte(MUT_PUT_EFFECTIVE_REPLICAS);
        out.writeUTF(m.deploymentName());
        out.writeInt(m.replicas());
      }
      case StateMutation.PutNodeRegistration m -> {
        out.writeByte(MUT_PUT_NODE_REGISTRATION);
        writeNodeRegistration(out, m.registration());
      }
      case StateMutation.PutTenant m -> {
        out.writeByte(MUT_PUT_TENANT);
        writeTenant(out, m.tenant());
      }
      case StateMutation.RemoveTenant m -> {
        out.writeByte(MUT_REMOVE_TENANT);
        out.writeUTF(m.id());
      }
      case StateMutation.PutQuotaViolation m -> {
        out.writeByte(MUT_PUT_QUOTA_VIOLATION);
        out.writeUTF(m.deploymentName());
        out.writeBoolean(m.violating());
      }
      case StateMutation.PutConfigEntry m -> {
        out.writeByte(MUT_PUT_CONFIG_ENTRY);
        writeConfigEntry(out, m.entry());
      }
      case StateMutation.RemoveConfigEntry m -> {
        out.writeByte(MUT_REMOVE_CONFIG_ENTRY);
        out.writeUTF(m.tenantId());
        out.writeUTF(m.key());
      }
      case StateMutation.PutRole m -> {
        out.writeByte(MUT_PUT_ROLE);
        writeRole(out, m.role());
      }
      case StateMutation.RemoveRole m -> {
        out.writeByte(MUT_REMOVE_ROLE);
        out.writeUTF(m.name());
      }
      case StateMutation.PutRoleBinding m -> {
        out.writeByte(MUT_PUT_ROLE_BINDING);
        writeRoleBinding(out, m.binding());
      }
      case StateMutation.RemoveRoleBinding m -> {
        out.writeByte(MUT_REMOVE_ROLE_BINDING);
        out.writeUTF(m.id());
      }
      case StateMutation.PutAccount m -> {
        out.writeByte(MUT_PUT_ACCOUNT);
        writeAccount(out, m.account());
      }
      case StateMutation.RemoveAccount m -> {
        out.writeByte(MUT_REMOVE_ACCOUNT);
        out.writeUTF(m.username());
      }
    }
  }

  private static StateMutation readStateMutation(DataInputStream in) throws IOException {
    byte tag = in.readByte();
    return switch (tag) {
      case MUT_PUT_DEPLOYMENT -> new StateMutation.PutDeployment(readDeploymentSpec(in));
      case MUT_REMOVE_DEPLOYMENT -> new StateMutation.RemoveDeployment(in.readUTF());
      case MUT_PUT_ASSIGNMENT -> new StateMutation.PutAssignment(readInstanceAssignment(in));
      case MUT_REMOVE_ASSIGNMENT -> new StateMutation.RemoveAssignment(in.readUTF(), in.readInt());
      case MUT_PUT_ROLLING_INDEX -> new StateMutation.PutRollingIndex(in.readUTF(), in.readInt());
      case MUT_CLEAR_ROLLING_INDEX -> new StateMutation.ClearRollingIndex(in.readUTF());
      case MUT_PUT_EFFECTIVE_REPLICAS ->
          new StateMutation.PutEffectiveReplicas(in.readUTF(), in.readInt());
      case MUT_PUT_NODE_REGISTRATION ->
          new StateMutation.PutNodeRegistration(readNodeRegistration(in));
      case MUT_PUT_TENANT -> new StateMutation.PutTenant(readTenant(in));
      case MUT_REMOVE_TENANT -> new StateMutation.RemoveTenant(in.readUTF());
      case MUT_PUT_QUOTA_VIOLATION ->
          new StateMutation.PutQuotaViolation(in.readUTF(), in.readBoolean());
      case MUT_PUT_CONFIG_ENTRY -> new StateMutation.PutConfigEntry(readConfigEntry(in));
      case MUT_REMOVE_CONFIG_ENTRY ->
          new StateMutation.RemoveConfigEntry(in.readUTF(), in.readUTF());
      case MUT_PUT_ROLE -> new StateMutation.PutRole(readRole(in));
      case MUT_REMOVE_ROLE -> new StateMutation.RemoveRole(in.readUTF());
      case MUT_PUT_ROLE_BINDING -> new StateMutation.PutRoleBinding(readRoleBinding(in));
      case MUT_REMOVE_ROLE_BINDING -> new StateMutation.RemoveRoleBinding(in.readUTF());
      case MUT_PUT_ACCOUNT -> new StateMutation.PutAccount(readAccount(in));
      case MUT_REMOVE_ACCOUNT -> new StateMutation.RemoveAccount(in.readUTF());
      default -> throw new IllegalArgumentException("unknown StateMutation tag: " + tag);
    };
  }

  // ---- domain type encode/decode ----

  private static void writeModuleId(DataOutputStream out, ModuleId id) throws IOException {
    out.writeUTF(id.name());
    out.writeUTF(id.version().toString());
  }

  private static ModuleId readModuleId(DataInputStream in) throws IOException {
    return new ModuleId(in.readUTF(), Version.parse(in.readUTF()));
  }

  private static void writeDeploymentSpec(DataOutputStream out, DeploymentSpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    writeModuleId(out, spec.moduleId());
    out.writeUTF(spec.artifactPath());
    out.writeInt(spec.replicas());
    writePlacementConstraints(out, spec.placement());
    writeOptionalAutoscalePolicy(out, spec.autoscale());
    writeOptionalString(out, spec.tenantId());
  }

  private static DeploymentSpec readDeploymentSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    int replicas = in.readInt();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<AutoscalePolicy> autoscale = readOptionalAutoscalePolicy(in);
    Optional<String> tenantId = readOptionalString(in);
    return new DeploymentSpec(
        name, moduleId, artifactPath, replicas, placement, autoscale, tenantId);
  }

  private static void writePlacementConstraints(DataOutputStream out, PlacementConstraints pc)
      throws IOException {
    out.writeBoolean(pc.antiAffinityAcrossNodes());
    Optional<Set<String>> labels = pc.requiredNodeLabels();
    out.writeBoolean(labels.isPresent());
    if (labels.isPresent()) {
      out.writeInt(labels.get().size());
      for (String label : labels.get()) {
        out.writeUTF(label);
      }
    }
  }

  private static PlacementConstraints readPlacementConstraints(DataInputStream in)
      throws IOException {
    boolean antiAffinity = in.readBoolean();
    boolean hasLabels = in.readBoolean();
    Optional<Set<String>> labels = Optional.empty();
    if (hasLabels) {
      int count = in.readInt();
      Set<String> set = new LinkedHashSet<>();
      for (int i = 0; i < count; i++) {
        set.add(in.readUTF());
      }
      labels = Optional.of(set);
    }
    return new PlacementConstraints(labels, antiAffinity);
  }

  private static void writeOptionalAutoscalePolicy(
      DataOutputStream out, Optional<AutoscalePolicy> policy) throws IOException {
    out.writeBoolean(policy.isPresent());
    if (policy.isPresent()) {
      AutoscalePolicy p = policy.get();
      out.writeInt(p.minReplicas());
      out.writeInt(p.maxReplicas());
      out.writeInt(p.targetCpuUtilizationPercent());
    }
  }

  private static Optional<AutoscalePolicy> readOptionalAutoscalePolicy(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    return Optional.of(new AutoscalePolicy(in.readInt(), in.readInt(), in.readInt()));
  }

  private static void writeOptionalString(DataOutputStream out, Optional<String> value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeUTF(value.get());
    }
  }

  private static Optional<String> readOptionalString(DataInputStream in) throws IOException {
    return in.readBoolean() ? Optional.of(in.readUTF()) : Optional.empty();
  }

  private static void writeInstanceAssignment(DataOutputStream out, InstanceAssignment assignment)
      throws IOException {
    out.writeUTF(assignment.deploymentName());
    out.writeInt(assignment.instanceIndex());
    out.writeUTF(assignment.nodeId());
    writeModuleId(out, assignment.moduleId());
    out.writeUTF(assignment.artifactPath());
  }

  private static InstanceAssignment readInstanceAssignment(DataInputStream in) throws IOException {
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    return new InstanceAssignment(deploymentName, instanceIndex, nodeId, moduleId, artifactPath);
  }

  private static void writeNodeRegistration(DataOutputStream out, NodeRegistration registration)
      throws IOException {
    out.writeUTF(registration.nodeId());
    Set<IsolationTier> tiers = registration.capabilities().supportedTiers();
    out.writeInt(tiers.size());
    for (IsolationTier tier : tiers) {
      out.writeUTF(tier.name());
    }
    out.writeUTF(registration.apiAddress().orElse(""));
  }

  private static NodeRegistration readNodeRegistration(DataInputStream in) throws IOException {
    String nodeId = in.readUTF();
    int count = in.readInt();
    Set<IsolationTier> tiers = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      tiers.add(IsolationTier.valueOf(in.readUTF()));
    }
    String apiAddress = in.readUTF();
    return new NodeRegistration(
        nodeId,
        new NodeCapabilities(tiers),
        apiAddress.isEmpty() ? Optional.empty() : Optional.of(apiAddress));
  }

  private static void writeTenant(DataOutputStream out, Tenant tenant) throws IOException {
    out.writeUTF(tenant.id());
    out.writeLong(tenant.quota().maxMemoryBytes());
    out.writeLong(tenant.quota().maxCpuMillicores());
    out.writeInt(tenant.quota().maxInstances());
  }

  private static Tenant readTenant(DataInputStream in) throws IOException {
    String id = in.readUTF();
    long maxMemoryBytes = in.readLong();
    long maxCpuMillicores = in.readLong();
    int maxInstances = in.readInt();
    return new Tenant(id, new ResourceQuota(maxMemoryBytes, maxCpuMillicores, maxInstances));
  }

  private static void writeConfigEntry(DataOutputStream out, ConfigEntry entry) throws IOException {
    out.writeUTF(entry.tenantId());
    out.writeUTF(entry.key());
    writeBytes(out, entry.value());
    out.writeBoolean(entry.encrypted());
  }

  private static ConfigEntry readConfigEntry(DataInputStream in) throws IOException {
    String tenantId = in.readUTF();
    String key = in.readUTF();
    byte[] value = readBytes(in);
    boolean encrypted = in.readBoolean();
    return new ConfigEntry(tenantId, key, value, encrypted);
  }

  // com.gimle.core.authz.Role fully qualified throughout this file -- this package already
  // declares its own Role (a Raft node's FOLLOWER/CANDIDATE/LEADER state), which silently shadows
  // an unqualified single-type-import of the RBAC Role of the same simple name.
  private static void writeRole(DataOutputStream out, com.gimle.core.authz.Role role)
      throws IOException {
    out.writeUTF(role.name());
    out.writeInt(role.permissions().size());
    for (Permission p : role.permissions()) {
      writePermission(out, p);
    }
  }

  private static com.gimle.core.authz.Role readRole(DataInputStream in) throws IOException {
    String name = in.readUTF();
    int count = in.readInt();
    Set<Permission> permissions = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      permissions.add(readPermission(in));
    }
    return new com.gimle.core.authz.Role(name, permissions);
  }

  private static void writePermission(DataOutputStream out, Permission permission)
      throws IOException {
    out.writeUTF(permission.resource().name());
    out.writeUTF(permission.verb().name());
    writeOptionalString(out, permission.tenantScope());
  }

  private static Permission readPermission(DataInputStream in) throws IOException {
    ResourceKind resource = ResourceKind.valueOf(in.readUTF());
    Verb verb = Verb.valueOf(in.readUTF());
    Optional<String> tenantScope = readOptionalString(in);
    return new Permission(resource, verb, tenantScope);
  }

  private static void writeRoleBinding(DataOutputStream out, RoleBinding binding)
      throws IOException {
    out.writeUTF(binding.id());
    out.writeUTF(binding.subject());
    out.writeUTF(binding.roleName());
  }

  private static RoleBinding readRoleBinding(DataInputStream in) throws IOException {
    return new RoleBinding(in.readUTF(), in.readUTF(), in.readUTF());
  }

  private static void writeAccount(DataOutputStream out, Account account) throws IOException {
    out.writeUTF(account.username());
    writeBytes(out, account.passwordHash());
  }

  private static Account readAccount(DataInputStream in) throws IOException {
    String username = in.readUTF();
    byte[] passwordHash = readBytes(in);
    return new Account(username, passwordHash);
  }

  private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int length = in.readInt();
    checkFrameLength(length);
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }

  // ---- StateSnapshot: standalone byte[] payload, not RaftRpc-framed ----

  public static byte[] encodeSnapshot(StateSnapshot snapshot) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeInt(snapshot.deployments().size());
      for (DeploymentSpec spec : snapshot.deployments()) {
        writeDeploymentSpec(out, spec);
      }
      out.writeInt(snapshot.assignments().size());
      for (InstanceAssignment assignment : snapshot.assignments()) {
        writeInstanceAssignment(out, assignment);
      }
      out.writeInt(snapshot.nodeRegistrations().size());
      for (NodeRegistration registration : snapshot.nodeRegistrations()) {
        writeNodeRegistration(out, registration);
      }
      out.writeInt(snapshot.rollingIndices().size());
      for (Map.Entry<String, Integer> e : snapshot.rollingIndices().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue());
      }
      out.writeInt(snapshot.effectiveReplicas().size());
      for (Map.Entry<String, Integer> e : snapshot.effectiveReplicas().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue());
      }
      out.writeInt(snapshot.tenants().size());
      for (Tenant tenant : snapshot.tenants()) {
        writeTenant(out, tenant);
      }
      out.writeInt(snapshot.quotaViolatingDeployments().size());
      for (String name : snapshot.quotaViolatingDeployments()) {
        out.writeUTF(name);
      }
      out.writeInt(snapshot.configEntries().size());
      for (ConfigEntry entry : snapshot.configEntries()) {
        writeConfigEntry(out, entry);
      }
      out.writeInt(snapshot.roles().size());
      for (com.gimle.core.authz.Role role : snapshot.roles()) {
        writeRole(out, role);
      }
      out.writeInt(snapshot.roleBindings().size());
      for (RoleBinding binding : snapshot.roleBindings()) {
        writeRoleBinding(out, binding);
      }
      out.writeInt(snapshot.accounts().size());
      for (Account account : snapshot.accounts()) {
        writeAccount(out, account);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  public static StateSnapshot decodeSnapshot(byte[] bytes) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      List<DeploymentSpec> deployments = new ArrayList<>();
      int deploymentCount = in.readInt();
      for (int i = 0; i < deploymentCount; i++) {
        deployments.add(readDeploymentSpec(in));
      }
      List<InstanceAssignment> assignments = new ArrayList<>();
      int assignmentCount = in.readInt();
      for (int i = 0; i < assignmentCount; i++) {
        assignments.add(readInstanceAssignment(in));
      }
      List<NodeRegistration> registrations = new ArrayList<>();
      int registrationCount = in.readInt();
      for (int i = 0; i < registrationCount; i++) {
        registrations.add(readNodeRegistration(in));
      }
      Map<String, Integer> rollingIndices = new LinkedHashMap<>();
      int rollingCount = in.readInt();
      for (int i = 0; i < rollingCount; i++) {
        rollingIndices.put(in.readUTF(), in.readInt());
      }
      Map<String, Integer> effectiveReplicas = new LinkedHashMap<>();
      int effectiveCount = in.readInt();
      for (int i = 0; i < effectiveCount; i++) {
        effectiveReplicas.put(in.readUTF(), in.readInt());
      }
      List<Tenant> tenants = new ArrayList<>();
      int tenantCount = in.readInt();
      for (int i = 0; i < tenantCount; i++) {
        tenants.add(readTenant(in));
      }
      Set<String> quotaViolating = new LinkedHashSet<>();
      int quotaCount = in.readInt();
      for (int i = 0; i < quotaCount; i++) {
        quotaViolating.add(in.readUTF());
      }
      List<ConfigEntry> configEntries = new ArrayList<>();
      int configCount = in.readInt();
      for (int i = 0; i < configCount; i++) {
        configEntries.add(readConfigEntry(in));
      }
      List<com.gimle.core.authz.Role> roles = new ArrayList<>();
      int roleCount = in.readInt();
      for (int i = 0; i < roleCount; i++) {
        roles.add(readRole(in));
      }
      List<RoleBinding> roleBindings = new ArrayList<>();
      int roleBindingCount = in.readInt();
      for (int i = 0; i < roleBindingCount; i++) {
        roleBindings.add(readRoleBinding(in));
      }
      List<Account> accounts = new ArrayList<>();
      int accountCount = in.readInt();
      for (int i = 0; i < accountCount; i++) {
        accounts.add(readAccount(in));
      }
      return new StateSnapshot(
          deployments,
          assignments,
          registrations,
          rollingIndices,
          effectiveReplicas,
          tenants,
          quotaViolating,
          configEntries,
          roles,
          roleBindings,
          accounts);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
