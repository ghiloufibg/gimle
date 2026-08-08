package com.gimle.mimir.codec;

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
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Binary (de)serialization for the domain types shared by {@link com.gimle.mimir.raft.RaftCodec}
 * (the Raft log/RPC wire format) and {@link com.gimle.mimir.rpc.StoreCodec} (the client-facing
 * {@code StoreRpc} wire format) -- pulled out once both codecs needed the exact same {@code
 * DeploymentSpec}/{@code InstanceAssignment}/{@code NodeRegistration}/{@code Tenant}/{@code
 * ConfigEntry}/RBAC encoding, rather than the second codec copying ~150 lines from the first.
 * Deliberately distinct from the *transport*-plumbing sharing (accept-loop, socket lifecycle, TLS)
 * that stays duplicated between {@code RaftTransport} and {@code StoreTransport} for now (see the
 * etcd-store-extraction design doc §4.3) -- this is data encoding, not networking, and the DRY case
 * for it was immediate rather than deferred.
 */
public final class DomainCodec {

  /**
   * Generous upper bound for any single length-prefixed byte-array field either codec ever reads --
   * far below what a corrupted or adversarial peer could otherwise force an allocation of.
   */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private DomainCodec() {}

  public static void writeModuleId(DataOutputStream out, ModuleId id) throws IOException {
    out.writeUTF(id.name());
    out.writeUTF(id.version().toString());
  }

  public static ModuleId readModuleId(DataInputStream in) throws IOException {
    return new ModuleId(in.readUTF(), Version.parse(in.readUTF()));
  }

  public static void writeDeploymentSpec(DataOutputStream out, DeploymentSpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    writeModuleId(out, spec.moduleId());
    out.writeUTF(spec.artifactPath());
    out.writeInt(spec.replicas());
    writePlacementConstraints(out, spec.placement());
    writeOptionalAutoscalePolicy(out, spec.autoscale());
    writeOptionalString(out, spec.tenantId());
  }

  public static DeploymentSpec readDeploymentSpec(DataInputStream in) throws IOException {
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

  public static void writePlacementConstraints(DataOutputStream out, PlacementConstraints pc)
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

  public static PlacementConstraints readPlacementConstraints(DataInputStream in)
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

  public static void writeOptionalAutoscalePolicy(
      DataOutputStream out, Optional<AutoscalePolicy> policy) throws IOException {
    out.writeBoolean(policy.isPresent());
    if (policy.isPresent()) {
      AutoscalePolicy p = policy.get();
      out.writeInt(p.minReplicas());
      out.writeInt(p.maxReplicas());
      out.writeInt(p.targetCpuUtilizationPercent());
    }
  }

  public static Optional<AutoscalePolicy> readOptionalAutoscalePolicy(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    return Optional.of(new AutoscalePolicy(in.readInt(), in.readInt(), in.readInt()));
  }

  public static void writeOptionalString(DataOutputStream out, Optional<String> value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeUTF(value.get());
    }
  }

  public static Optional<String> readOptionalString(DataInputStream in) throws IOException {
    return in.readBoolean() ? Optional.of(in.readUTF()) : Optional.empty();
  }

  public static void writeInstanceAssignment(DataOutputStream out, InstanceAssignment assignment)
      throws IOException {
    out.writeUTF(assignment.deploymentName());
    out.writeInt(assignment.instanceIndex());
    out.writeUTF(assignment.nodeId());
    writeModuleId(out, assignment.moduleId());
    out.writeUTF(assignment.artifactPath());
  }

  public static InstanceAssignment readInstanceAssignment(DataInputStream in) throws IOException {
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    return new InstanceAssignment(deploymentName, instanceIndex, nodeId, moduleId, artifactPath);
  }

  public static void writeNodeRegistration(DataOutputStream out, NodeRegistration registration)
      throws IOException {
    out.writeUTF(registration.nodeId());
    Set<IsolationTier> tiers = registration.capabilities().supportedTiers();
    out.writeInt(tiers.size());
    for (IsolationTier tier : tiers) {
      out.writeUTF(tier.name());
    }
    out.writeUTF(registration.apiAddress().orElse(""));
    Set<String> labels = registration.capabilities().labels();
    out.writeInt(labels.size());
    for (String label : labels) {
      out.writeUTF(label);
    }
  }

  public static NodeRegistration readNodeRegistration(DataInputStream in) throws IOException {
    String nodeId = in.readUTF();
    int count = in.readInt();
    Set<IsolationTier> tiers = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      tiers.add(IsolationTier.valueOf(in.readUTF()));
    }
    String apiAddress = in.readUTF();
    Set<String> labels = new LinkedHashSet<>();
    int labelCount = in.readInt();
    for (int i = 0; i < labelCount; i++) {
      labels.add(in.readUTF());
    }
    return new NodeRegistration(
        nodeId,
        new NodeCapabilities(tiers, labels),
        apiAddress.isEmpty() ? Optional.empty() : Optional.of(apiAddress));
  }

  public static void writeTenant(DataOutputStream out, Tenant tenant) throws IOException {
    out.writeUTF(tenant.id());
    out.writeLong(tenant.quota().maxMemoryBytes());
    out.writeLong(tenant.quota().maxCpuMillicores());
    out.writeInt(tenant.quota().maxInstances());
  }

  public static Tenant readTenant(DataInputStream in) throws IOException {
    String id = in.readUTF();
    long maxMemoryBytes = in.readLong();
    long maxCpuMillicores = in.readLong();
    int maxInstances = in.readInt();
    return new Tenant(id, new ResourceQuota(maxMemoryBytes, maxCpuMillicores, maxInstances));
  }

  public static void writeConfigEntry(DataOutputStream out, ConfigEntry entry) throws IOException {
    out.writeUTF(entry.tenantId());
    out.writeUTF(entry.key());
    writeBytes(out, entry.value());
    out.writeBoolean(entry.encrypted());
  }

  public static ConfigEntry readConfigEntry(DataInputStream in) throws IOException {
    String tenantId = in.readUTF();
    String key = in.readUTF();
    byte[] value = readBytes(in);
    boolean encrypted = in.readBoolean();
    return new ConfigEntry(tenantId, key, value, encrypted);
  }

  // com.gimle.core.authz.Role fully qualified throughout this file -- com.gimle.mimir.raft
  // (RaftCodec's own package) declares its own Role (a Raft node's FOLLOWER/CANDIDATE/LEADER
  // state) of the same simple name; this class is imported from that package, so staying fully
  // qualified here avoids the same silent-shadowing trap RaftCodec's own javadoc already
  // documents, even though this file itself has no local Role type to collide with.
  public static void writeRole(DataOutputStream out, com.gimle.core.authz.Role role)
      throws IOException {
    out.writeUTF(role.name());
    out.writeInt(role.permissions().size());
    for (Permission p : role.permissions()) {
      writePermission(out, p);
    }
  }

  public static com.gimle.core.authz.Role readRole(DataInputStream in) throws IOException {
    String name = in.readUTF();
    int count = in.readInt();
    Set<Permission> permissions = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      permissions.add(readPermission(in));
    }
    return new com.gimle.core.authz.Role(name, permissions);
  }

  public static void writePermission(DataOutputStream out, Permission permission)
      throws IOException {
    out.writeUTF(permission.resource().name());
    out.writeUTF(permission.verb().name());
    writeOptionalString(out, permission.tenantScope());
  }

  public static Permission readPermission(DataInputStream in) throws IOException {
    ResourceKind resource = ResourceKind.valueOf(in.readUTF());
    Verb verb = Verb.valueOf(in.readUTF());
    Optional<String> tenantScope = readOptionalString(in);
    return new Permission(resource, verb, tenantScope);
  }

  public static void writeRoleBinding(DataOutputStream out, RoleBinding binding)
      throws IOException {
    out.writeUTF(binding.id());
    out.writeUTF(binding.subject());
    out.writeUTF(binding.roleName());
  }

  public static RoleBinding readRoleBinding(DataInputStream in) throws IOException {
    return new RoleBinding(in.readUTF(), in.readUTF(), in.readUTF());
  }

  public static void writeAccount(DataOutputStream out, Account account) throws IOException {
    out.writeUTF(account.username());
    writeBytes(out, account.passwordHash());
  }

  public static Account readAccount(DataInputStream in) throws IOException {
    String username = in.readUTF();
    byte[] passwordHash = readBytes(in);
    return new Account(username, passwordHash);
  }

  // NodeHeartbeat/ObservedHeartbeat: never Raft-replicated (RaftCodec never needed these), but
  // travel over StoreRpc's PutHeartbeat/GetNodeHeartbeat -- the one write §4.4/§4.6 of the
  // etcd-store-extraction design doc call out as leader-only but non-replicated, same as today.
  public static void writeResourceUsageSnapshot(DataOutputStream out, ResourceUsageSnapshot usage)
      throws IOException {
    out.writeLong(usage.totalMemoryBytes());
    out.writeLong(usage.assignedMemoryBytes());
    out.writeLong(usage.totalCpuMillicores());
    out.writeLong(usage.assignedCpuMillicores());
  }

  public static ResourceUsageSnapshot readResourceUsageSnapshot(DataInputStream in)
      throws IOException {
    return new ResourceUsageSnapshot(in.readLong(), in.readLong(), in.readLong(), in.readLong());
  }

  public static void writeInstanceObservation(DataOutputStream out, InstanceObservation obs)
      throws IOException {
    out.writeUTF(obs.deploymentName());
    out.writeInt(obs.instanceIndex());
    writeModuleId(out, obs.moduleId());
    out.writeUTF(obs.lifecycleState());
    out.writeBoolean(obs.alive());
    out.writeBoolean(obs.ready());
    out.writeDouble(obs.requestRatePerSecond());
    out.writeInt(obs.queueDepth());
    out.writeLong(obs.cpuMillicoresUsed());
    out.writeLong(obs.memoryBytesUsed());
    out.writeDouble(obs.errorRatePerSecond());
  }

  public static InstanceObservation readInstanceObservation(DataInputStream in) throws IOException {
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    var moduleId = readModuleId(in);
    String lifecycleState = in.readUTF();
    boolean alive = in.readBoolean();
    boolean ready = in.readBoolean();
    double requestRatePerSecond = in.readDouble();
    int queueDepth = in.readInt();
    long cpuMillicoresUsed = in.readLong();
    long memoryBytesUsed = in.readLong();
    double errorRatePerSecond = in.readDouble();
    return new InstanceObservation(
        deploymentName,
        instanceIndex,
        moduleId,
        lifecycleState,
        alive,
        ready,
        requestRatePerSecond,
        queueDepth,
        cpuMillicoresUsed,
        memoryBytesUsed,
        errorRatePerSecond);
  }

  public static void writeNodeHeartbeat(DataOutputStream out, NodeHeartbeat heartbeat)
      throws IOException {
    out.writeUTF(heartbeat.nodeId());
    writeResourceUsageSnapshot(out, heartbeat.capacity());
    out.writeInt(heartbeat.instances().size());
    for (InstanceObservation obs : heartbeat.instances()) {
      writeInstanceObservation(out, obs);
    }
  }

  public static NodeHeartbeat readNodeHeartbeat(DataInputStream in) throws IOException {
    String nodeId = in.readUTF();
    ResourceUsageSnapshot capacity = readResourceUsageSnapshot(in);
    int count = in.readInt();
    List<InstanceObservation> instances = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      instances.add(readInstanceObservation(in));
    }
    return new NodeHeartbeat(nodeId, capacity, instances);
  }

  public static void writeObservedHeartbeat(DataOutputStream out, ObservedHeartbeat observed)
      throws IOException {
    writeNodeHeartbeat(out, observed.heartbeat());
    out.writeUTF(observed.receivedAt().toString());
  }

  public static ObservedHeartbeat readObservedHeartbeat(DataInputStream in) throws IOException {
    NodeHeartbeat heartbeat = readNodeHeartbeat(in);
    Instant receivedAt = Instant.parse(in.readUTF());
    return new ObservedHeartbeat(heartbeat, receivedAt);
  }

  public static void writeReconcilerInstanceState(
      DataOutputStream out, ReconcilerInstanceState state) throws IOException {
    out.writeUTF(state.deploymentName());
    out.writeInt(state.instanceIndex());
    out.writeInt(state.attemptsInWindow());
    out.writeLong(state.windowStartEpochMilli());
    out.writeLong(state.nextAllowedAttemptEpochMilli());
    out.writeBoolean(state.pendingRetry());
    out.writeBoolean(state.permanentlyFailed());
    out.writeLong(state.firstSeenMissingAtEpochMilli());
  }

  public static ReconcilerInstanceState readReconcilerInstanceState(DataInputStream in)
      throws IOException {
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    int attemptsInWindow = in.readInt();
    long windowStartEpochMilli = in.readLong();
    long nextAllowedAttemptEpochMilli = in.readLong();
    boolean pendingRetry = in.readBoolean();
    boolean permanentlyFailed = in.readBoolean();
    long firstSeenMissingAtEpochMilli = in.readLong();
    return new ReconcilerInstanceState(
        deploymentName,
        instanceIndex,
        attemptsInWindow,
        windowStartEpochMilli,
        nextAllowedAttemptEpochMilli,
        pendingRetry,
        permanentlyFailed,
        firstSeenMissingAtEpochMilli);
  }

  public static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  public static byte[] readBytes(DataInputStream in) throws IOException {
    int length = in.readInt();
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }
}
