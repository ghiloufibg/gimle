package com.gimle.mimir.raft;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.codec.Frames;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.codec.DomainCodec;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateSnapshot;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Encodes/decodes a {@link RaftRpc} the same way {@code gimle-fabric}'s {@code FabricCodec} encodes
 * a {@code FabricFrame}: a 4-byte big-endian length prefix, a one-byte wire-protocol version, a
 * one-byte type tag, then {@link DataOutputStream} primitive fields, with every {@code byte[]}
 * field itself separately length-prefixed. A {@link LogEntry} carrying a {@link StateMutation} is
 * exactly the same kind of arbitrary-byte payload {@code InvokeRequest}'s {@code serializedArgs}
 * already is, so this reuses that framing shape rather than inventing a third one. Every
 * domain-type field ({@code DeploymentSpec}, {@code InstanceAssignment}, RBAC types, ...) delegates
 * to {@link DomainCodec}, shared with {@code StoreCodec} -- this class owns only the Raft-specific
 * RPC/log-entry framing.
 *
 * <p>The version byte is checked before any version-specific field is decoded, exactly the way
 * {@code FabricCodec} checks its own: a Raft peer either understands {@link #CURRENT_VERSION} or
 * rejects the RPC outright rather than misdecoding it -- what actually matters during a rolling
 * upgrade, when a leader on one binary version and a follower on another are live on the wire at
 * the same time.
 *
 * <p>Also encodes/decodes a {@link StateSnapshot} as a standalone byte array -- the payload an
 * {@link InstallSnapshot} RPC carries chunk by chunk to a follower catching up live, and what
 * {@link RaftLog} separately persists to disk after a local compaction; carries its own version
 * byte for the same reason, since it's decoded independently of {@link #read}'s framing.
 */
public final class RaftCodec {

  /**
   * The only wire-protocol version any writer produces today; bump this when either {@link RaftRpc}
   * or {@link StateSnapshot}'s own encoding shape changes.
   */
  private static final int CURRENT_VERSION = 1;

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
  private static final byte MUT_ADD_ROLLING_INDEX = 4;
  private static final byte MUT_REMOVE_ROLLING_INDEX = 5;
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
  private static final byte MUT_PUT_RECONCILER_INSTANCE_STATE = 19;
  private static final byte MUT_REMOVE_RECONCILER_INSTANCE_STATE = 20;
  private static final byte MUT_PUT_NODE_CORDON = 21;
  private static final byte MUT_APPEND_INSTANCE_EVENT = 22;
  private static final byte MUT_APPEND_AUDIT_EVENT = 23;
  private static final byte MUT_PUT_JOB_SPEC = 24;
  private static final byte MUT_REMOVE_JOB_SPEC = 25;
  private static final byte MUT_PUT_JOB_RUN = 26;
  private static final byte MUT_REMOVE_JOB_RUN = 27;
  private static final byte MUT_PUT_JOB_PHASE = 28;
  private static final byte MUT_PUT_CRONJOB_SPEC = 29;
  private static final byte MUT_REMOVE_CRONJOB_SPEC = 30;
  private static final byte MUT_PUT_CRONJOB_LAST_SCHEDULE = 31;
  private static final byte MUT_PUT_DAEMONSET_SPEC = 32;
  private static final byte MUT_REMOVE_DAEMONSET_SPEC = 33;
  private static final byte MUT_PUT_DAEMONSET_ASSIGNMENT = 34;
  private static final byte MUT_REMOVE_DAEMONSET_ASSIGNMENT = 35;
  private static final byte MUT_ADD_ROLLING_DAEMONSET_NODE = 36;
  private static final byte MUT_REMOVE_ROLLING_DAEMONSET_NODE = 37;
  private static final byte MUT_PUT_STATEFULSET_SPEC = 38;
  private static final byte MUT_REMOVE_STATEFULSET_SPEC = 39;
  private static final byte MUT_PUT_STATEFULSET_ASSIGNMENT = 40;
  private static final byte MUT_REMOVE_STATEFULSET_ASSIGNMENT = 41;
  private static final byte MUT_PUT_ROLLING_STATEFULSET_INDEX = 42;
  private static final byte MUT_CLEAR_ROLLING_STATEFULSET_INDEX = 43;
  private static final byte MUT_PUT_STATEFULSET_INDEX_NODE = 44;
  private static final byte MUT_REMOVE_STATEFULSET_INDEX_NODE = 45;
  private static final byte MUT_ADD_SURGE_INDEX = 46;
  private static final byte MUT_REMOVE_SURGE_INDEX = 47;
  private static final byte MUT_PUT_SERVICE = 48;
  private static final byte MUT_REMOVE_SERVICE = 49;
  private static final byte MUT_PUT_NETWORK_POLICY = 50;
  private static final byte MUT_REMOVE_NETWORK_POLICY = 51;
  private static final byte MUT_APPEND_CONTROLLER_REVISION = 52;
  private static final byte MUT_PUT_LIMIT_RANGE = 53;
  private static final byte MUT_REMOVE_LIMIT_RANGE = 54;
  private static final byte MUT_PUT_LIMIT_RANGE_VIOLATION = 55;
  private static final byte MUT_BATCH = 56;
  private static final byte MUT_PUT_CERTIFICATE_REVOCATION = 57;
  private static final byte MUT_PUT_WORKLOAD_TOKEN = 58;
  private static final byte MUT_REMOVE_WORKLOAD_TOKEN = 59;
  private static final byte MUT_PUT_JOB_RUN_SUMMARY = 60;
  private static final byte MUT_PUT_NODE_TAINT = 61;
  private static final byte MUT_PUT_KIND_DEFINITION = 62;
  private static final byte MUT_REMOVE_KIND_DEFINITION = 63;
  private static final byte MUT_PUT_CUSTOM_RESOURCE = 64;
  private static final byte MUT_REMOVE_CUSTOM_RESOURCE = 65;
  private static final byte MUT_PUT_CUSTOM_RESOURCE_STATUS = 66;
  private static final byte MUT_PUT_WORKLOAD_HEALTH_STATE = 67;
  private static final byte MUT_REMOVE_WORKLOAD_HEALTH_STATE = 68;
  private static final byte MUT_PUT_SESSION_REVOCATION = 69;
  private static final byte MUT_RESTORE_SNAPSHOT = 70;

  private static final byte PAYLOAD_STATE_MUTATION = 0;
  private static final byte PAYLOAD_MEMBERSHIP_CHANGE = 1;
  private static final byte PAYLOAD_NOOP = 2;

  /**
   * Generous upper bound for any single length-prefixed frame this codec ever produces (a {@link
   * StateSnapshot} is the largest payload in practice) -- far below what a corrupted or adversarial
   * peer could otherwise force this reader to allocate.
   */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private RaftCodec() {}

  private static void checkFrameLength(int length) {
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
  }

  // ---- top-level RaftRpc framing (length-prefixed, matching FabricCodec) ----

  public static void write(OutputStream out, RaftRpc rpc) throws IOException {
    Frames.writeFrame(out, encodeRpcBody(rpc));
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
      out.writeByte(CURRENT_VERSION);
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
          out.writeLong(v.offset());
          DomainCodec.writeBytes(out, v.data());
          out.writeBoolean(v.done());
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
      int version = in.readByte();
      GimleCodecException.checkVersion(version, CURRENT_VERSION);
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
          long offset = in.readLong();
          byte[] data = DomainCodec.readBytes(in);
          boolean done = in.readBoolean();
          yield new InstallSnapshot(
              term, leaderId, lastIncludedIndex, lastIncludedTerm, offset, data, done);
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
    switch (entry.payload()) {
      case StateMutation mutation -> {
        out.writeByte(PAYLOAD_STATE_MUTATION);
        writeStateMutation(out, mutation);
      }
      case MembershipChange change -> {
        out.writeByte(PAYLOAD_MEMBERSHIP_CHANGE);
        writeMembershipChange(out, change);
      }
      case Noop ignored -> out.writeByte(PAYLOAD_NOOP);
    }
  }

  static LogEntry readLogEntry(DataInputStream in) throws IOException {
    long term = in.readLong();
    long index = in.readLong();
    byte payloadKind = in.readByte();
    RaftLogPayload payload =
        switch (payloadKind) {
          case PAYLOAD_STATE_MUTATION -> readStateMutation(in);
          case PAYLOAD_MEMBERSHIP_CHANGE -> readMembershipChange(in);
          case PAYLOAD_NOOP -> new Noop();
          default ->
              throw new IllegalArgumentException("unknown Raft log payload kind: " + payloadKind);
        };
    return new LogEntry(term, index, payload);
  }

  private static void writeMembershipChange(DataOutputStream out, MembershipChange change)
      throws IOException {
    out.writeInt(change.peers().size());
    for (Map.Entry<String, PeerAddress> e : change.peers().entrySet()) {
      out.writeUTF(e.getKey());
      PeerAddress address = e.getValue();
      out.writeUTF(address.host());
      out.writeInt(address.raftPort());
      out.writeInt(address.clientPort());
    }
    out.writeInt(change.learners().size());
    for (String learnerId : change.learners()) {
      out.writeUTF(learnerId);
    }
  }

  private static MembershipChange readMembershipChange(DataInputStream in) throws IOException {
    int count = in.readInt();
    Map<String, PeerAddress> peers = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      String id = in.readUTF();
      String host = in.readUTF();
      int raftPort = in.readInt();
      int clientPort = in.readInt();
      peers.put(id, new PeerAddress(host, raftPort, clientPort));
    }
    int learnerCount = in.readInt();
    Set<String> learners = new LinkedHashSet<>();
    for (int i = 0; i < learnerCount; i++) {
      learners.add(in.readUTF());
    }
    return new MembershipChange(peers, learners);
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

  /**
   * Encodes a standalone {@link StateMutation}, without a {@link LogEntry}'s {@code term}/{@code
   * index} wrapper -- what a {@code StoreRpc.Propose} request carries over the wire before it's
   * ever appended to a log at all.
   */
  public static byte[] encodeMutation(StateMutation mutation) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      writeStateMutation(new DataOutputStream(buffer), mutation);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  public static StateMutation decodeMutation(byte[] bytes) {
    try {
      return readStateMutation(new DataInputStream(new ByteArrayInputStream(bytes)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeStateMutation(DataOutputStream out, StateMutation mutation)
      throws IOException {
    switch (mutation) {
      case StateMutation.PutDeployment m -> {
        out.writeByte(MUT_PUT_DEPLOYMENT);
        DomainCodec.writeDeploymentSpec(out, m.spec());
        out.writeLong(m.expectedGeneration());
      }
      case StateMutation.RemoveDeployment m -> {
        out.writeByte(MUT_REMOVE_DEPLOYMENT);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
        out.writeLong(m.expectedGeneration());
      }
      case StateMutation.PutService m -> {
        out.writeByte(MUT_PUT_SERVICE);
        DomainCodec.writeServiceSpec(out, m.spec());
      }
      case StateMutation.RemoveService m -> {
        out.writeByte(MUT_REMOVE_SERVICE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutNetworkPolicy m -> {
        out.writeByte(MUT_PUT_NETWORK_POLICY);
        DomainCodec.writeNetworkPolicySpec(out, m.spec());
      }
      case StateMutation.RemoveNetworkPolicy m -> {
        out.writeByte(MUT_REMOVE_NETWORK_POLICY);
        out.writeUTF(m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.AppendControllerRevision m -> {
        out.writeByte(MUT_APPEND_CONTROLLER_REVISION);
        DomainCodec.writeControllerRevision(out, m.revision());
      }
      case StateMutation.PutLimitRange m -> {
        out.writeByte(MUT_PUT_LIMIT_RANGE);
        DomainCodec.writeLimitRangeSpec(out, m.spec());
      }
      case StateMutation.RemoveLimitRange m -> {
        out.writeByte(MUT_REMOVE_LIMIT_RANGE);
        out.writeUTF(m.tenantId());
      }
      case StateMutation.PutLimitRangeViolation m -> {
        out.writeByte(MUT_PUT_LIMIT_RANGE_VIOLATION);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeUTF(m.reason());
      }
      case StateMutation.Batch m -> {
        out.writeByte(MUT_BATCH);
        out.writeInt(m.mutations().size());
        for (StateMutation nested : m.mutations()) {
          writeStateMutation(out, nested);
        }
      }
      case StateMutation.PutAssignment m -> {
        out.writeByte(MUT_PUT_ASSIGNMENT);
        DomainCodec.writeInstanceAssignment(out, m.assignment());
      }
      case StateMutation.RemoveAssignment m -> {
        out.writeByte(MUT_REMOVE_ASSIGNMENT);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.AddRollingIndex m -> {
        out.writeByte(MUT_ADD_ROLLING_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.RemoveRollingIndex m -> {
        out.writeByte(MUT_REMOVE_ROLLING_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.AddSurgeIndex m -> {
        out.writeByte(MUT_ADD_SURGE_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.surgeIndex());
        out.writeInt(m.targetIndex());
      }
      case StateMutation.RemoveSurgeIndex m -> {
        out.writeByte(MUT_REMOVE_SURGE_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.surgeIndex());
      }
      case StateMutation.PutEffectiveReplicas m -> {
        out.writeByte(MUT_PUT_EFFECTIVE_REPLICAS);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.replicas());
      }
      case StateMutation.PutNodeRegistration m -> {
        out.writeByte(MUT_PUT_NODE_REGISTRATION);
        DomainCodec.writeNodeRegistration(out, m.registration());
      }
      case StateMutation.PutTenant m -> {
        out.writeByte(MUT_PUT_TENANT);
        DomainCodec.writeTenant(out, m.tenant());
      }
      case StateMutation.RemoveTenant m -> {
        out.writeByte(MUT_REMOVE_TENANT);
        out.writeUTF(m.id());
      }
      case StateMutation.PutQuotaViolation m -> {
        out.writeByte(MUT_PUT_QUOTA_VIOLATION);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeBoolean(m.violating());
      }
      case StateMutation.PutConfigEntry m -> {
        out.writeByte(MUT_PUT_CONFIG_ENTRY);
        DomainCodec.writeConfigEntry(out, m.entry());
      }
      case StateMutation.RemoveConfigEntry m -> {
        out.writeByte(MUT_REMOVE_CONFIG_ENTRY);
        out.writeUTF(m.tenantId());
        out.writeUTF(m.key());
      }
      case StateMutation.PutRole m -> {
        out.writeByte(MUT_PUT_ROLE);
        DomainCodec.writeRole(out, m.role());
      }
      case StateMutation.RemoveRole m -> {
        out.writeByte(MUT_REMOVE_ROLE);
        out.writeUTF(m.name());
      }
      case StateMutation.PutRoleBinding m -> {
        out.writeByte(MUT_PUT_ROLE_BINDING);
        DomainCodec.writeRoleBinding(out, m.binding());
      }
      case StateMutation.RemoveRoleBinding m -> {
        out.writeByte(MUT_REMOVE_ROLE_BINDING);
        out.writeUTF(m.id());
      }
      case StateMutation.PutAccount m -> {
        out.writeByte(MUT_PUT_ACCOUNT);
        DomainCodec.writeAccount(out, m.account());
      }
      case StateMutation.RemoveAccount m -> {
        out.writeByte(MUT_REMOVE_ACCOUNT);
        out.writeUTF(m.username());
      }
      case StateMutation.PutReconcilerInstanceState m -> {
        out.writeByte(MUT_PUT_RECONCILER_INSTANCE_STATE);
        DomainCodec.writeReconcilerInstanceState(out, m.state());
      }
      case StateMutation.RemoveReconcilerInstanceState m -> {
        out.writeByte(MUT_REMOVE_RECONCILER_INSTANCE_STATE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.PutWorkloadHealthState m -> {
        out.writeByte(MUT_PUT_WORKLOAD_HEALTH_STATE);
        DomainCodec.writeWorkloadHealthState(out, m.state());
      }
      case StateMutation.RemoveWorkloadHealthState m -> {
        out.writeByte(MUT_REMOVE_WORKLOAD_HEALTH_STATE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.workloadKind());
        out.writeUTF(m.workloadName());
        out.writeUTF(m.slot());
      }
      case StateMutation.PutNodeCordon m -> {
        out.writeByte(MUT_PUT_NODE_CORDON);
        out.writeUTF(m.nodeId());
        out.writeBoolean(m.cordoned());
      }
      case StateMutation.PutNodeTaint m -> {
        out.writeByte(MUT_PUT_NODE_TAINT);
        out.writeUTF(m.nodeId());
        out.writeUTF(m.tenantId());
        out.writeBoolean(m.tainted());
      }
      case StateMutation.PutCertificateRevocation m -> {
        out.writeByte(MUT_PUT_CERTIFICATE_REVOCATION);
        out.writeUTF(m.serialNumber());
        out.writeBoolean(m.revoked());
      }
      case StateMutation.PutSessionRevocation m -> {
        out.writeByte(MUT_PUT_SESSION_REVOCATION);
        out.writeUTF(m.username());
        out.writeLong(m.revokedBeforeEpochMilli());
      }
      case StateMutation.PutWorkloadToken m -> {
        out.writeByte(MUT_PUT_WORKLOAD_TOKEN);
        DomainCodec.writeWorkloadTokenRecord(out, m.record());
        out.writeLong(m.mintedAtEpochMilli());
      }
      case StateMutation.RemoveWorkloadToken m -> {
        out.writeByte(MUT_REMOVE_WORKLOAD_TOKEN);
        out.writeUTF(m.key());
      }
      case StateMutation.AppendInstanceEvent m -> {
        out.writeByte(MUT_APPEND_INSTANCE_EVENT);
        DomainCodec.writeOptionalString(out, m.tenantId());
        DomainCodec.writeInstanceEvent(out, m.event());
      }
      case StateMutation.AppendAuditEvent m -> {
        out.writeByte(MUT_APPEND_AUDIT_EVENT);
        DomainCodec.writeAuditEvent(out, m.event());
      }
      case StateMutation.PutJobSpec m -> {
        out.writeByte(MUT_PUT_JOB_SPEC);
        DomainCodec.writeJobSpec(out, m.spec());
      }
      case StateMutation.RemoveJobSpec m -> {
        out.writeByte(MUT_REMOVE_JOB_SPEC);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutJobRun m -> {
        out.writeByte(MUT_PUT_JOB_RUN);
        DomainCodec.writeJobRun(out, m.run());
      }
      case StateMutation.RemoveJobRun m -> {
        out.writeByte(MUT_REMOVE_JOB_RUN);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.jobName());
        out.writeInt(m.attempt());
      }
      case StateMutation.PutJobPhase m -> {
        out.writeByte(MUT_PUT_JOB_PHASE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.jobName());
        out.writeUTF(m.phase().name());
      }
      case StateMutation.PutJobRunSummary m -> {
        out.writeByte(MUT_PUT_JOB_RUN_SUMMARY);
        DomainCodec.writeJobRunSummary(out, m.summary());
      }
      case StateMutation.PutCronJobSpec m -> {
        out.writeByte(MUT_PUT_CRONJOB_SPEC);
        DomainCodec.writeCronJobSpec(out, m.spec());
      }
      case StateMutation.RemoveCronJobSpec m -> {
        out.writeByte(MUT_REMOVE_CRONJOB_SPEC);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutCronJobLastSchedule m -> {
        out.writeByte(MUT_PUT_CRONJOB_LAST_SCHEDULE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
        out.writeLong(m.lastScheduleTime().toEpochMilli());
      }
      case StateMutation.PutDaemonSetSpec m -> {
        out.writeByte(MUT_PUT_DAEMONSET_SPEC);
        DomainCodec.writeDaemonSetSpec(out, m.spec());
      }
      case StateMutation.RemoveDaemonSetSpec m -> {
        out.writeByte(MUT_REMOVE_DAEMONSET_SPEC);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutDaemonSetAssignment m -> {
        out.writeByte(MUT_PUT_DAEMONSET_ASSIGNMENT);
        DomainCodec.writeDaemonSetAssignment(out, m.assignment());
      }
      case StateMutation.RemoveDaemonSetAssignment m -> {
        out.writeByte(MUT_REMOVE_DAEMONSET_ASSIGNMENT);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.daemonSetName());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.AddRollingDaemonSetNode m -> {
        out.writeByte(MUT_ADD_ROLLING_DAEMONSET_NODE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.daemonSetName());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.RemoveRollingDaemonSetNode m -> {
        out.writeByte(MUT_REMOVE_ROLLING_DAEMONSET_NODE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.daemonSetName());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.PutStatefulSetSpec m -> {
        out.writeByte(MUT_PUT_STATEFULSET_SPEC);
        DomainCodec.writeStatefulSetSpec(out, m.spec());
      }
      case StateMutation.RemoveStatefulSetSpec m -> {
        out.writeByte(MUT_REMOVE_STATEFULSET_SPEC);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutStatefulSetAssignment m -> {
        out.writeByte(MUT_PUT_STATEFULSET_ASSIGNMENT);
        DomainCodec.writeStatefulSetAssignment(out, m.assignment());
      }
      case StateMutation.RemoveStatefulSetAssignment m -> {
        out.writeByte(MUT_REMOVE_STATEFULSET_ASSIGNMENT);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.statefulSetName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.PutRollingStatefulSetIndex m -> {
        out.writeByte(MUT_PUT_ROLLING_STATEFULSET_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.statefulSetName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.ClearRollingStatefulSetIndex m -> {
        out.writeByte(MUT_CLEAR_ROLLING_STATEFULSET_INDEX);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.statefulSetName());
      }
      case StateMutation.PutStatefulSetIndexNode m -> {
        out.writeByte(MUT_PUT_STATEFULSET_INDEX_NODE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.statefulSetName());
        out.writeInt(m.instanceIndex());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.RemoveStatefulSetIndexNode m -> {
        out.writeByte(MUT_REMOVE_STATEFULSET_INDEX_NODE);
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.statefulSetName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.PutKindDefinition m -> {
        out.writeByte(MUT_PUT_KIND_DEFINITION);
        DomainCodec.writeKindDefinitionSpec(out, m.spec());
        out.writeLong(m.expectedGeneration());
      }
      case StateMutation.RemoveKindDefinition m -> {
        out.writeByte(MUT_REMOVE_KIND_DEFINITION);
        out.writeUTF(m.kindName());
      }
      case StateMutation.PutCustomResource m -> {
        out.writeByte(MUT_PUT_CUSTOM_RESOURCE);
        DomainCodec.writeCustomResource(out, m.resource());
        out.writeLong(m.expectedGeneration());
      }
      case StateMutation.RemoveCustomResource m -> {
        out.writeByte(MUT_REMOVE_CUSTOM_RESOURCE);
        out.writeUTF(m.kindName());
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
      }
      case StateMutation.PutCustomResourceStatus m -> {
        out.writeByte(MUT_PUT_CUSTOM_RESOURCE_STATUS);
        out.writeUTF(m.kindName());
        DomainCodec.writeOptionalString(out, m.tenantId());
        out.writeUTF(m.name());
        DomainCodec.writeBytes(out, m.statusJson());
      }
      case StateMutation.RestoreSnapshot m -> {
        out.writeByte(MUT_RESTORE_SNAPSHOT);
        // Reuses encodeSnapshot's own already-versioned encoding wholesale rather than a second
        // inline copy of StateSnapshot's field-by-field writer -- the nested version byte this
        // embeds is redundant with the outer LogEntry's (both currently 1), but keeps the
        // snapshot bytes independently decodable via decodeSnapshot on their own, not just as
        // part of a full LogEntry.
        DomainCodec.writeBytes(out, encodeSnapshot(m.snapshot()));
      }
    }
  }

  private static StateMutation readStateMutation(DataInputStream in) throws IOException {
    byte tag = in.readByte();
    return switch (tag) {
      case MUT_PUT_DEPLOYMENT -> {
        DeploymentSpec spec = DomainCodec.readDeploymentSpec(in);
        yield new StateMutation.PutDeployment(spec, in.readLong());
      }
      case MUT_REMOVE_DEPLOYMENT -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String name = in.readUTF();
        yield new StateMutation.RemoveDeployment(tenantId, name, in.readLong());
      }
      case MUT_PUT_SERVICE -> new StateMutation.PutService(DomainCodec.readServiceSpec(in));
      case MUT_REMOVE_SERVICE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveService(tenantId, in.readUTF());
      }
      case MUT_PUT_NETWORK_POLICY ->
          new StateMutation.PutNetworkPolicy(DomainCodec.readNetworkPolicySpec(in));
      case MUT_REMOVE_NETWORK_POLICY -> {
        String tenantId = in.readUTF();
        yield new StateMutation.RemoveNetworkPolicy(tenantId, in.readUTF());
      }
      case MUT_APPEND_CONTROLLER_REVISION ->
          new StateMutation.AppendControllerRevision(DomainCodec.readControllerRevision(in));
      case MUT_PUT_LIMIT_RANGE ->
          new StateMutation.PutLimitRange(DomainCodec.readLimitRangeSpec(in));
      case MUT_REMOVE_LIMIT_RANGE -> new StateMutation.RemoveLimitRange(in.readUTF());
      case MUT_PUT_LIMIT_RANGE_VIOLATION -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.PutLimitRangeViolation(tenantId, deploymentName, in.readUTF());
      }
      case MUT_BATCH -> {
        int count = in.readInt();
        List<StateMutation> nested = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          nested.add(readStateMutation(in));
        }
        yield new StateMutation.Batch(nested);
      }
      case MUT_PUT_ASSIGNMENT ->
          new StateMutation.PutAssignment(DomainCodec.readInstanceAssignment(in));
      case MUT_REMOVE_ASSIGNMENT -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.RemoveAssignment(tenantId, deploymentName, in.readInt());
      }
      case MUT_ADD_ROLLING_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.AddRollingIndex(tenantId, deploymentName, in.readInt());
      }
      case MUT_REMOVE_ROLLING_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.RemoveRollingIndex(tenantId, deploymentName, in.readInt());
      }
      case MUT_ADD_SURGE_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.AddSurgeIndex(tenantId, deploymentName, in.readInt(), in.readInt());
      }
      case MUT_REMOVE_SURGE_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.RemoveSurgeIndex(tenantId, deploymentName, in.readInt());
      }
      case MUT_PUT_EFFECTIVE_REPLICAS -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.PutEffectiveReplicas(tenantId, deploymentName, in.readInt());
      }
      case MUT_PUT_NODE_REGISTRATION ->
          new StateMutation.PutNodeRegistration(DomainCodec.readNodeRegistration(in));
      case MUT_PUT_TENANT -> new StateMutation.PutTenant(DomainCodec.readTenant(in));
      case MUT_REMOVE_TENANT -> new StateMutation.RemoveTenant(in.readUTF());
      case MUT_PUT_QUOTA_VIOLATION -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.PutQuotaViolation(tenantId, deploymentName, in.readBoolean());
      }
      case MUT_PUT_CONFIG_ENTRY ->
          new StateMutation.PutConfigEntry(DomainCodec.readConfigEntry(in));
      case MUT_REMOVE_CONFIG_ENTRY ->
          new StateMutation.RemoveConfigEntry(in.readUTF(), in.readUTF());
      case MUT_PUT_ROLE -> new StateMutation.PutRole(DomainCodec.readRole(in));
      case MUT_REMOVE_ROLE -> new StateMutation.RemoveRole(in.readUTF());
      case MUT_PUT_ROLE_BINDING ->
          new StateMutation.PutRoleBinding(DomainCodec.readRoleBinding(in));
      case MUT_REMOVE_ROLE_BINDING -> new StateMutation.RemoveRoleBinding(in.readUTF());
      case MUT_PUT_ACCOUNT -> new StateMutation.PutAccount(DomainCodec.readAccount(in));
      case MUT_REMOVE_ACCOUNT -> new StateMutation.RemoveAccount(in.readUTF());
      case MUT_PUT_RECONCILER_INSTANCE_STATE ->
          new StateMutation.PutReconcilerInstanceState(DomainCodec.readReconcilerInstanceState(in));
      case MUT_REMOVE_RECONCILER_INSTANCE_STATE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String deploymentName = in.readUTF();
        yield new StateMutation.RemoveReconcilerInstanceState(
            tenantId, deploymentName, in.readInt());
      }
      case MUT_PUT_WORKLOAD_HEALTH_STATE ->
          new StateMutation.PutWorkloadHealthState(DomainCodec.readWorkloadHealthState(in));
      case MUT_REMOVE_WORKLOAD_HEALTH_STATE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String workloadKind = in.readUTF();
        String workloadName = in.readUTF();
        yield new StateMutation.RemoveWorkloadHealthState(
            tenantId, workloadKind, workloadName, in.readUTF());
      }
      case MUT_PUT_NODE_CORDON -> new StateMutation.PutNodeCordon(in.readUTF(), in.readBoolean());
      case MUT_PUT_NODE_TAINT ->
          new StateMutation.PutNodeTaint(in.readUTF(), in.readUTF(), in.readBoolean());
      case MUT_PUT_CERTIFICATE_REVOCATION ->
          new StateMutation.PutCertificateRevocation(in.readUTF(), in.readBoolean());
      case MUT_PUT_SESSION_REVOCATION ->
          new StateMutation.PutSessionRevocation(in.readUTF(), in.readLong());
      case MUT_PUT_WORKLOAD_TOKEN ->
          new StateMutation.PutWorkloadToken(
              DomainCodec.readWorkloadTokenRecord(in), in.readLong());
      case MUT_REMOVE_WORKLOAD_TOKEN -> new StateMutation.RemoveWorkloadToken(in.readUTF());
      case MUT_APPEND_INSTANCE_EVENT -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.AppendInstanceEvent(tenantId, DomainCodec.readInstanceEvent(in));
      }
      case MUT_APPEND_AUDIT_EVENT ->
          new StateMutation.AppendAuditEvent(DomainCodec.readAuditEvent(in));
      case MUT_PUT_JOB_SPEC -> new StateMutation.PutJobSpec(DomainCodec.readJobSpec(in));
      case MUT_REMOVE_JOB_SPEC -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveJobSpec(tenantId, in.readUTF());
      }
      case MUT_PUT_JOB_RUN -> new StateMutation.PutJobRun(DomainCodec.readJobRun(in));
      case MUT_REMOVE_JOB_RUN -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String jobName = in.readUTF();
        yield new StateMutation.RemoveJobRun(tenantId, jobName, in.readInt());
      }
      case MUT_PUT_JOB_PHASE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String jobName = in.readUTF();
        yield new StateMutation.PutJobPhase(tenantId, jobName, JobPhase.valueOf(in.readUTF()));
      }
      case MUT_PUT_JOB_RUN_SUMMARY ->
          new StateMutation.PutJobRunSummary(DomainCodec.readJobRunSummary(in));
      case MUT_PUT_CRONJOB_SPEC ->
          new StateMutation.PutCronJobSpec(DomainCodec.readCronJobSpec(in));
      case MUT_REMOVE_CRONJOB_SPEC -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveCronJobSpec(tenantId, in.readUTF());
      }
      case MUT_PUT_CRONJOB_LAST_SCHEDULE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String name = in.readUTF();
        yield new StateMutation.PutCronJobLastSchedule(
            tenantId, name, Instant.ofEpochMilli(in.readLong()));
      }
      case MUT_PUT_DAEMONSET_SPEC ->
          new StateMutation.PutDaemonSetSpec(DomainCodec.readDaemonSetSpec(in));
      case MUT_REMOVE_DAEMONSET_SPEC -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveDaemonSetSpec(tenantId, in.readUTF());
      }
      case MUT_PUT_DAEMONSET_ASSIGNMENT ->
          new StateMutation.PutDaemonSetAssignment(DomainCodec.readDaemonSetAssignment(in));
      case MUT_REMOVE_DAEMONSET_ASSIGNMENT -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String daemonSetName = in.readUTF();
        yield new StateMutation.RemoveDaemonSetAssignment(tenantId, daemonSetName, in.readUTF());
      }
      case MUT_ADD_ROLLING_DAEMONSET_NODE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String daemonSetName = in.readUTF();
        yield new StateMutation.AddRollingDaemonSetNode(tenantId, daemonSetName, in.readUTF());
      }
      case MUT_REMOVE_ROLLING_DAEMONSET_NODE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String daemonSetName = in.readUTF();
        yield new StateMutation.RemoveRollingDaemonSetNode(tenantId, daemonSetName, in.readUTF());
      }
      case MUT_PUT_STATEFULSET_SPEC ->
          new StateMutation.PutStatefulSetSpec(DomainCodec.readStatefulSetSpec(in));
      case MUT_REMOVE_STATEFULSET_SPEC -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveStatefulSetSpec(tenantId, in.readUTF());
      }
      case MUT_PUT_STATEFULSET_ASSIGNMENT ->
          new StateMutation.PutStatefulSetAssignment(DomainCodec.readStatefulSetAssignment(in));
      case MUT_REMOVE_STATEFULSET_ASSIGNMENT -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String statefulSetName = in.readUTF();
        yield new StateMutation.RemoveStatefulSetAssignment(
            tenantId, statefulSetName, in.readInt());
      }
      case MUT_PUT_ROLLING_STATEFULSET_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String statefulSetName = in.readUTF();
        yield new StateMutation.PutRollingStatefulSetIndex(tenantId, statefulSetName, in.readInt());
      }
      case MUT_CLEAR_ROLLING_STATEFULSET_INDEX -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.ClearRollingStatefulSetIndex(tenantId, in.readUTF());
      }
      case MUT_PUT_STATEFULSET_INDEX_NODE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String statefulSetName = in.readUTF();
        int instanceIndex = in.readInt();
        yield new StateMutation.PutStatefulSetIndexNode(
            tenantId, statefulSetName, instanceIndex, in.readUTF());
      }
      case MUT_REMOVE_STATEFULSET_INDEX_NODE -> {
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String statefulSetName = in.readUTF();
        yield new StateMutation.RemoveStatefulSetIndexNode(tenantId, statefulSetName, in.readInt());
      }
      case MUT_PUT_KIND_DEFINITION -> {
        KindDefinitionSpec spec = DomainCodec.readKindDefinitionSpec(in);
        yield new StateMutation.PutKindDefinition(spec, in.readLong());
      }
      case MUT_REMOVE_KIND_DEFINITION -> new StateMutation.RemoveKindDefinition(in.readUTF());
      case MUT_PUT_CUSTOM_RESOURCE -> {
        CustomResource resource = DomainCodec.readCustomResource(in);
        yield new StateMutation.PutCustomResource(resource, in.readLong());
      }
      case MUT_REMOVE_CUSTOM_RESOURCE -> {
        String kindName = in.readUTF();
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        yield new StateMutation.RemoveCustomResource(kindName, tenantId, in.readUTF());
      }
      case MUT_PUT_CUSTOM_RESOURCE_STATUS -> {
        String kindName = in.readUTF();
        Optional<String> tenantId = DomainCodec.readOptionalString(in);
        String name = in.readUTF();
        yield new StateMutation.PutCustomResourceStatus(
            kindName, tenantId, name, DomainCodec.readBytes(in));
      }
      case MUT_RESTORE_SNAPSHOT ->
          new StateMutation.RestoreSnapshot(decodeSnapshot(DomainCodec.readBytes(in)));
      default -> throw new IllegalArgumentException("unknown StateMutation tag: " + tag);
    };
  }

  // ---- StateSnapshot: standalone byte[] payload, not RaftRpc-framed ----

  public static byte[] encodeSnapshot(StateSnapshot snapshot) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeByte(CURRENT_VERSION);
      out.writeInt(snapshot.deployments().size());
      for (DeploymentSpec spec : snapshot.deployments()) {
        DomainCodec.writeDeploymentSpec(out, spec);
      }
      out.writeInt(snapshot.deploymentGenerations().size());
      for (Map.Entry<String, Long> e : snapshot.deploymentGenerations().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeLong(e.getValue());
      }
      out.writeInt(snapshot.assignments().size());
      for (InstanceAssignment assignment : snapshot.assignments()) {
        DomainCodec.writeInstanceAssignment(out, assignment);
      }
      out.writeInt(snapshot.jobSpecs().size());
      for (JobSpec spec : snapshot.jobSpecs()) {
        DomainCodec.writeJobSpec(out, spec);
      }
      out.writeInt(snapshot.jobRuns().size());
      for (JobRun run : snapshot.jobRuns()) {
        DomainCodec.writeJobRun(out, run);
      }
      out.writeInt(snapshot.jobPhases().size());
      for (Map.Entry<String, JobPhase> e : snapshot.jobPhases().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeUTF(e.getValue().name());
      }
      out.writeInt(snapshot.jobRunSummaries().size());
      for (JobRunSummary summary : snapshot.jobRunSummaries()) {
        DomainCodec.writeJobRunSummary(out, summary);
      }
      out.writeInt(snapshot.cronJobSpecs().size());
      for (CronJobSpec spec : snapshot.cronJobSpecs()) {
        DomainCodec.writeCronJobSpec(out, spec);
      }
      out.writeInt(snapshot.cronJobLastSchedule().size());
      for (Map.Entry<String, Instant> e : snapshot.cronJobLastSchedule().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeLong(e.getValue().toEpochMilli());
      }
      out.writeInt(snapshot.daemonSetSpecs().size());
      for (DaemonSetSpec spec : snapshot.daemonSetSpecs()) {
        DomainCodec.writeDaemonSetSpec(out, spec);
      }
      out.writeInt(snapshot.daemonSetAssignments().size());
      for (DaemonSetAssignment assignment : snapshot.daemonSetAssignments()) {
        DomainCodec.writeDaemonSetAssignment(out, assignment);
      }
      out.writeInt(snapshot.rollingDaemonSetNodes().size());
      for (Map.Entry<String, Set<String>> e : snapshot.rollingDaemonSetNodes().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue().size());
        for (String nodeId : e.getValue()) {
          out.writeUTF(nodeId);
        }
      }
      out.writeInt(snapshot.statefulSetSpecs().size());
      for (StatefulSetSpec spec : snapshot.statefulSetSpecs()) {
        DomainCodec.writeStatefulSetSpec(out, spec);
      }
      out.writeInt(snapshot.statefulSetAssignments().size());
      for (StatefulSetAssignment assignment : snapshot.statefulSetAssignments()) {
        DomainCodec.writeStatefulSetAssignment(out, assignment);
      }
      out.writeInt(snapshot.rollingStatefulSetIndices().size());
      for (Map.Entry<String, Integer> e : snapshot.rollingStatefulSetIndices().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue());
      }
      out.writeInt(snapshot.statefulSetIndexNodes().size());
      for (Map.Entry<String, String> e : snapshot.statefulSetIndexNodes().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeUTF(e.getValue());
      }
      out.writeInt(snapshot.nodeRegistrations().size());
      for (NodeRegistration registration : snapshot.nodeRegistrations()) {
        DomainCodec.writeNodeRegistration(out, registration);
      }
      out.writeInt(snapshot.rollingIndices().size());
      for (Map.Entry<String, Set<Integer>> e : snapshot.rollingIndices().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue().size());
        for (int index : e.getValue()) {
          out.writeInt(index);
        }
      }
      out.writeInt(snapshot.surgeIndices().size());
      for (Map.Entry<String, Map<Integer, Integer>> e : snapshot.surgeIndices().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue().size());
        for (Map.Entry<Integer, Integer> surgeToTarget : e.getValue().entrySet()) {
          out.writeInt(surgeToTarget.getKey());
          out.writeInt(surgeToTarget.getValue());
        }
      }
      out.writeInt(snapshot.effectiveReplicas().size());
      for (Map.Entry<String, Integer> e : snapshot.effectiveReplicas().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue());
      }
      out.writeInt(snapshot.tenants().size());
      for (Tenant tenant : snapshot.tenants()) {
        DomainCodec.writeTenant(out, tenant);
      }
      out.writeInt(snapshot.quotaViolatingDeployments().size());
      for (String name : snapshot.quotaViolatingDeployments()) {
        out.writeUTF(name);
      }
      out.writeInt(snapshot.configEntries().size());
      for (ConfigEntry entry : snapshot.configEntries()) {
        DomainCodec.writeConfigEntry(out, entry);
      }
      out.writeInt(snapshot.roles().size());
      // Fully qualified deliberately: this package already declares its own Role (a Raft node's
      // FOLLOWER/CANDIDATE/LEADER state), which shadows an unqualified single-type-import of the
      // RBAC com.gimle.core.authz.Role of the same simple name -- same-package types always win
      // Java's unqualified-name resolution over an import, silently, with no compile error at the
      // declaration site (only at first attempted use of the wrong type). See StateMutation.java's
      // own PutRole for the identical collision.
      for (com.gimle.core.authz.Role role : snapshot.roles()) {
        DomainCodec.writeRole(out, role);
      }
      out.writeInt(snapshot.roleBindings().size());
      for (RoleBinding binding : snapshot.roleBindings()) {
        DomainCodec.writeRoleBinding(out, binding);
      }
      out.writeInt(snapshot.accounts().size());
      for (Account account : snapshot.accounts()) {
        DomainCodec.writeAccount(out, account);
      }
      out.writeInt(snapshot.reconcilerInstanceStates().size());
      for (ReconcilerInstanceState state : snapshot.reconcilerInstanceStates()) {
        DomainCodec.writeReconcilerInstanceState(out, state);
      }
      out.writeInt(snapshot.cordonedNodes().size());
      for (String nodeId : snapshot.cordonedNodes()) {
        out.writeUTF(nodeId);
      }
      out.writeInt(snapshot.instanceEvents().size());
      for (Map.Entry<String, List<InstanceEvent>> e : snapshot.instanceEvents().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue().size());
        for (InstanceEvent event : e.getValue()) {
          DomainCodec.writeInstanceEvent(out, event);
        }
      }
      out.writeInt(snapshot.auditEvents().size());
      for (AuditEvent event : snapshot.auditEvents()) {
        DomainCodec.writeAuditEvent(out, event);
      }
      out.writeInt(snapshot.services().size());
      for (ServiceSpec spec : snapshot.services()) {
        DomainCodec.writeServiceSpec(out, spec);
      }
      out.writeInt(snapshot.networkPolicies().size());
      for (NetworkPolicySpec spec : snapshot.networkPolicies()) {
        DomainCodec.writeNetworkPolicySpec(out, spec);
      }
      out.writeInt(snapshot.controllerRevisions().size());
      for (ControllerRevision revision : snapshot.controllerRevisions()) {
        DomainCodec.writeControllerRevision(out, revision);
      }
      out.writeInt(snapshot.limitRanges().size());
      for (LimitRangeSpec spec : snapshot.limitRanges()) {
        DomainCodec.writeLimitRangeSpec(out, spec);
      }
      out.writeInt(snapshot.limitRangeViolations().size());
      for (Map.Entry<String, String> entry : snapshot.limitRangeViolations().entrySet()) {
        out.writeUTF(entry.getKey());
        out.writeUTF(entry.getValue());
      }
      out.writeInt(snapshot.revokedCertificateSerials().size());
      for (String serial : snapshot.revokedCertificateSerials()) {
        out.writeUTF(serial);
      }
      out.writeInt(snapshot.workloadTokens().size());
      for (WorkloadTokenRecord record : snapshot.workloadTokens()) {
        DomainCodec.writeWorkloadTokenRecord(out, record);
      }
      out.writeInt(snapshot.nodeTaints().size());
      for (Map.Entry<String, Set<String>> e : snapshot.nodeTaints().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeInt(e.getValue().size());
        for (String tenantId : e.getValue()) {
          out.writeUTF(tenantId);
        }
      }
      out.writeInt(snapshot.kindDefinitions().size());
      for (KindDefinitionSpec definition : snapshot.kindDefinitions()) {
        DomainCodec.writeKindDefinitionSpec(out, definition);
      }
      out.writeInt(snapshot.customResources().size());
      for (CustomResource resource : snapshot.customResources()) {
        DomainCodec.writeCustomResource(out, resource);
      }
      out.writeInt(snapshot.workloadHealthStates().size());
      for (WorkloadHealthState state : snapshot.workloadHealthStates()) {
        DomainCodec.writeWorkloadHealthState(out, state);
      }
      out.writeInt(snapshot.sessionRevokedBeforeEpochMilli().size());
      for (Map.Entry<String, Long> e : snapshot.sessionRevokedBeforeEpochMilli().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeLong(e.getValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  public static StateSnapshot decodeSnapshot(byte[] bytes) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      int version = in.readByte();
      GimleCodecException.checkVersion(version, CURRENT_VERSION);
      List<DeploymentSpec> deployments = new ArrayList<>();
      int deploymentCount = in.readInt();
      for (int i = 0; i < deploymentCount; i++) {
        deployments.add(DomainCodec.readDeploymentSpec(in));
      }
      Map<String, Long> deploymentGenerations = new LinkedHashMap<>();
      int deploymentGenerationCount = in.readInt();
      for (int i = 0; i < deploymentGenerationCount; i++) {
        deploymentGenerations.put(in.readUTF(), in.readLong());
      }
      List<InstanceAssignment> assignments = new ArrayList<>();
      int assignmentCount = in.readInt();
      for (int i = 0; i < assignmentCount; i++) {
        assignments.add(DomainCodec.readInstanceAssignment(in));
      }
      List<JobSpec> jobSpecs = new ArrayList<>();
      int jobSpecCount = in.readInt();
      for (int i = 0; i < jobSpecCount; i++) {
        jobSpecs.add(DomainCodec.readJobSpec(in));
      }
      List<JobRun> jobRuns = new ArrayList<>();
      int jobRunCount = in.readInt();
      for (int i = 0; i < jobRunCount; i++) {
        jobRuns.add(DomainCodec.readJobRun(in));
      }
      Map<String, JobPhase> jobPhases = new LinkedHashMap<>();
      int jobPhaseCount = in.readInt();
      for (int i = 0; i < jobPhaseCount; i++) {
        jobPhases.put(in.readUTF(), JobPhase.valueOf(in.readUTF()));
      }
      List<JobRunSummary> jobRunSummaries = new ArrayList<>();
      int jobRunSummaryCount = in.readInt();
      for (int i = 0; i < jobRunSummaryCount; i++) {
        jobRunSummaries.add(DomainCodec.readJobRunSummary(in));
      }
      List<CronJobSpec> cronJobSpecs = new ArrayList<>();
      int cronJobSpecCount = in.readInt();
      for (int i = 0; i < cronJobSpecCount; i++) {
        cronJobSpecs.add(DomainCodec.readCronJobSpec(in));
      }
      Map<String, Instant> cronJobLastSchedule = new LinkedHashMap<>();
      int cronJobLastScheduleCount = in.readInt();
      for (int i = 0; i < cronJobLastScheduleCount; i++) {
        cronJobLastSchedule.put(in.readUTF(), Instant.ofEpochMilli(in.readLong()));
      }
      List<DaemonSetSpec> daemonSetSpecs = new ArrayList<>();
      int daemonSetSpecCount = in.readInt();
      for (int i = 0; i < daemonSetSpecCount; i++) {
        daemonSetSpecs.add(DomainCodec.readDaemonSetSpec(in));
      }
      List<DaemonSetAssignment> daemonSetAssignments = new ArrayList<>();
      int daemonSetAssignmentCount = in.readInt();
      for (int i = 0; i < daemonSetAssignmentCount; i++) {
        daemonSetAssignments.add(DomainCodec.readDaemonSetAssignment(in));
      }
      Map<String, Set<String>> rollingDaemonSetNodes = new LinkedHashMap<>();
      int rollingDaemonSetNameCount = in.readInt();
      for (int i = 0; i < rollingDaemonSetNameCount; i++) {
        String daemonSetName = in.readUTF();
        Set<String> nodeIds = new LinkedHashSet<>();
        int nodeIdCount = in.readInt();
        for (int j = 0; j < nodeIdCount; j++) {
          nodeIds.add(in.readUTF());
        }
        rollingDaemonSetNodes.put(daemonSetName, nodeIds);
      }
      List<StatefulSetSpec> statefulSetSpecs = new ArrayList<>();
      int statefulSetSpecCount = in.readInt();
      for (int i = 0; i < statefulSetSpecCount; i++) {
        statefulSetSpecs.add(DomainCodec.readStatefulSetSpec(in));
      }
      List<StatefulSetAssignment> statefulSetAssignments = new ArrayList<>();
      int statefulSetAssignmentCount = in.readInt();
      for (int i = 0; i < statefulSetAssignmentCount; i++) {
        statefulSetAssignments.add(DomainCodec.readStatefulSetAssignment(in));
      }
      Map<String, Integer> rollingStatefulSetIndices = new LinkedHashMap<>();
      int rollingStatefulSetIndexCount = in.readInt();
      for (int i = 0; i < rollingStatefulSetIndexCount; i++) {
        rollingStatefulSetIndices.put(in.readUTF(), in.readInt());
      }
      Map<String, String> statefulSetIndexNodes = new LinkedHashMap<>();
      int statefulSetIndexNodeCount = in.readInt();
      for (int i = 0; i < statefulSetIndexNodeCount; i++) {
        statefulSetIndexNodes.put(in.readUTF(), in.readUTF());
      }
      List<NodeRegistration> registrations = new ArrayList<>();
      int registrationCount = in.readInt();
      for (int i = 0; i < registrationCount; i++) {
        registrations.add(DomainCodec.readNodeRegistration(in));
      }
      Map<String, Set<Integer>> rollingIndices = new LinkedHashMap<>();
      int rollingDeploymentCount = in.readInt();
      for (int i = 0; i < rollingDeploymentCount; i++) {
        String deploymentName = in.readUTF();
        Set<Integer> indices = new LinkedHashSet<>();
        int indexCount = in.readInt();
        for (int j = 0; j < indexCount; j++) {
          indices.add(in.readInt());
        }
        rollingIndices.put(deploymentName, indices);
      }
      Map<String, Map<Integer, Integer>> surgeIndices = new LinkedHashMap<>();
      int surgeDeploymentCount = in.readInt();
      for (int i = 0; i < surgeDeploymentCount; i++) {
        String deploymentName = in.readUTF();
        Map<Integer, Integer> indices = new LinkedHashMap<>();
        int surgeCount = in.readInt();
        for (int j = 0; j < surgeCount; j++) {
          int surgeIndex = in.readInt();
          int targetIndex = in.readInt();
          indices.put(surgeIndex, targetIndex);
        }
        surgeIndices.put(deploymentName, indices);
      }
      Map<String, Integer> effectiveReplicas = new LinkedHashMap<>();
      int effectiveCount = in.readInt();
      for (int i = 0; i < effectiveCount; i++) {
        effectiveReplicas.put(in.readUTF(), in.readInt());
      }
      List<Tenant> tenants = new ArrayList<>();
      int tenantCount = in.readInt();
      for (int i = 0; i < tenantCount; i++) {
        tenants.add(DomainCodec.readTenant(in));
      }
      Set<String> quotaViolating = new LinkedHashSet<>();
      int quotaCount = in.readInt();
      for (int i = 0; i < quotaCount; i++) {
        quotaViolating.add(in.readUTF());
      }
      List<ConfigEntry> configEntries = new ArrayList<>();
      int configCount = in.readInt();
      for (int i = 0; i < configCount; i++) {
        configEntries.add(DomainCodec.readConfigEntry(in));
      }
      // Fully qualified deliberately: same Role/Role collision as writeSnapshot above -- see the
      // comment there.
      List<com.gimle.core.authz.Role> roles = new ArrayList<>();
      int roleCount = in.readInt();
      for (int i = 0; i < roleCount; i++) {
        roles.add(DomainCodec.readRole(in));
      }
      List<RoleBinding> roleBindings = new ArrayList<>();
      int roleBindingCount = in.readInt();
      for (int i = 0; i < roleBindingCount; i++) {
        roleBindings.add(DomainCodec.readRoleBinding(in));
      }
      List<Account> accounts = new ArrayList<>();
      int accountCount = in.readInt();
      for (int i = 0; i < accountCount; i++) {
        accounts.add(DomainCodec.readAccount(in));
      }
      List<ReconcilerInstanceState> reconcilerInstanceStates = new ArrayList<>();
      int reconcilerInstanceStateCount = in.readInt();
      for (int i = 0; i < reconcilerInstanceStateCount; i++) {
        reconcilerInstanceStates.add(DomainCodec.readReconcilerInstanceState(in));
      }
      Set<String> cordonedNodes = new LinkedHashSet<>();
      int cordonedCount = in.readInt();
      for (int i = 0; i < cordonedCount; i++) {
        cordonedNodes.add(in.readUTF());
      }
      Map<String, List<InstanceEvent>> instanceEvents = new LinkedHashMap<>();
      int instanceEventKeyCount = in.readInt();
      for (int i = 0; i < instanceEventKeyCount; i++) {
        String key = in.readUTF();
        int eventCount = in.readInt();
        List<InstanceEvent> events = new ArrayList<>();
        for (int j = 0; j < eventCount; j++) {
          events.add(DomainCodec.readInstanceEvent(in));
        }
        instanceEvents.put(key, events);
      }
      List<AuditEvent> auditEvents = new ArrayList<>();
      int auditEventCount = in.readInt();
      for (int i = 0; i < auditEventCount; i++) {
        auditEvents.add(DomainCodec.readAuditEvent(in));
      }
      List<ServiceSpec> services = new ArrayList<>();
      int serviceCount = in.readInt();
      for (int i = 0; i < serviceCount; i++) {
        services.add(DomainCodec.readServiceSpec(in));
      }
      List<NetworkPolicySpec> networkPolicies = new ArrayList<>();
      int networkPolicyCount = in.readInt();
      for (int i = 0; i < networkPolicyCount; i++) {
        networkPolicies.add(DomainCodec.readNetworkPolicySpec(in));
      }
      List<ControllerRevision> controllerRevisions = new ArrayList<>();
      int controllerRevisionCount = in.readInt();
      for (int i = 0; i < controllerRevisionCount; i++) {
        controllerRevisions.add(DomainCodec.readControllerRevision(in));
      }
      List<LimitRangeSpec> limitRanges = new ArrayList<>();
      int limitRangeCount = in.readInt();
      for (int i = 0; i < limitRangeCount; i++) {
        limitRanges.add(DomainCodec.readLimitRangeSpec(in));
      }
      Map<String, String> limitRangeViolations = new LinkedHashMap<>();
      int limitRangeViolatingCount = in.readInt();
      for (int i = 0; i < limitRangeViolatingCount; i++) {
        String deploymentName = in.readUTF();
        String reason = in.readUTF();
        limitRangeViolations.put(deploymentName, reason);
      }
      Set<String> revokedCertificateSerials = new LinkedHashSet<>();
      int revokedSerialCount = in.readInt();
      for (int i = 0; i < revokedSerialCount; i++) {
        revokedCertificateSerials.add(in.readUTF());
      }
      List<WorkloadTokenRecord> workloadTokens = new ArrayList<>();
      int workloadTokenCount = in.readInt();
      for (int i = 0; i < workloadTokenCount; i++) {
        workloadTokens.add(DomainCodec.readWorkloadTokenRecord(in));
      }
      Map<String, Set<String>> nodeTaints = new LinkedHashMap<>();
      int nodeTaintCount = in.readInt();
      for (int i = 0; i < nodeTaintCount; i++) {
        String nodeId = in.readUTF();
        int taintedTenantCount = in.readInt();
        Set<String> tenantIds = new LinkedHashSet<>();
        for (int j = 0; j < taintedTenantCount; j++) {
          tenantIds.add(in.readUTF());
        }
        nodeTaints.put(nodeId, tenantIds);
      }
      List<KindDefinitionSpec> kindDefinitions = new ArrayList<>();
      int kindDefinitionCount = in.readInt();
      for (int i = 0; i < kindDefinitionCount; i++) {
        kindDefinitions.add(DomainCodec.readKindDefinitionSpec(in));
      }
      List<CustomResource> customResources = new ArrayList<>();
      int customResourceCount = in.readInt();
      for (int i = 0; i < customResourceCount; i++) {
        customResources.add(DomainCodec.readCustomResource(in));
      }
      List<WorkloadHealthState> workloadHealthStates = new ArrayList<>();
      int workloadHealthStateCount = in.readInt();
      for (int i = 0; i < workloadHealthStateCount; i++) {
        workloadHealthStates.add(DomainCodec.readWorkloadHealthState(in));
      }
      Map<String, Long> sessionRevokedBeforeEpochMilli = new LinkedHashMap<>();
      int sessionRevocationCount = in.readInt();
      for (int i = 0; i < sessionRevocationCount; i++) {
        sessionRevokedBeforeEpochMilli.put(in.readUTF(), in.readLong());
      }
      return new StateSnapshot(
          deployments,
          deploymentGenerations,
          assignments,
          jobSpecs,
          jobRuns,
          jobPhases,
          jobRunSummaries,
          cronJobSpecs,
          cronJobLastSchedule,
          daemonSetSpecs,
          daemonSetAssignments,
          rollingDaemonSetNodes,
          statefulSetSpecs,
          statefulSetAssignments,
          rollingStatefulSetIndices,
          statefulSetIndexNodes,
          registrations,
          rollingIndices,
          surgeIndices,
          effectiveReplicas,
          tenants,
          quotaViolating,
          configEntries,
          roles,
          roleBindings,
          accounts,
          reconcilerInstanceStates,
          cordonedNodes,
          instanceEvents,
          auditEvents,
          services,
          networkPolicies,
          controllerRevisions,
          limitRanges,
          limitRangeViolations,
          revokedCertificateSerials,
          workloadTokens,
          nodeTaints,
          kindDefinitions,
          customResources,
          workloadHealthStates,
          sessionRevokedBeforeEpochMilli);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
