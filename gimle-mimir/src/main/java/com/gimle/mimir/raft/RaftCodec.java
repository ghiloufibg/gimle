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
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StateSnapshot;
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
import java.util.Set;

/**
 * Encodes/decodes a {@link RaftRpc} the same way {@code gimle-fabric}'s {@code FabricCodec} encodes
 * a {@code FabricFrame}: a 4-byte big-endian length prefix, a one-byte type tag, then {@link
 * DataOutputStream} primitive fields, with every {@code byte[]} field itself separately
 * length-prefixed. A {@link LogEntry} carrying a {@link StateMutation} is exactly the same kind of
 * arbitrary-byte payload {@code InvokeRequest}'s {@code serializedArgs} already is, so this reuses
 * that framing shape rather than inventing a third one. Every domain-type field ({@code
 * DeploymentSpec}, {@code InstanceAssignment}, RBAC types, ...) delegates to {@link DomainCodec},
 * shared with {@code StoreCodec} -- this class owns only the Raft-specific RPC/log-entry framing.
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
  private static final byte MUT_PUT_ROLLING_DAEMONSET_NODE = 36;
  private static final byte MUT_CLEAR_ROLLING_DAEMONSET_NODE = 37;

  private static final byte PAYLOAD_STATE_MUTATION = 0;
  private static final byte PAYLOAD_MEMBERSHIP_CHANGE = 1;

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
    return new MembershipChange(peers);
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
      }
      case StateMutation.RemoveDeployment m -> {
        out.writeByte(MUT_REMOVE_DEPLOYMENT);
        out.writeUTF(m.name());
      }
      case StateMutation.PutAssignment m -> {
        out.writeByte(MUT_PUT_ASSIGNMENT);
        DomainCodec.writeInstanceAssignment(out, m.assignment());
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
        out.writeUTF(m.deploymentName());
        out.writeInt(m.instanceIndex());
      }
      case StateMutation.PutNodeCordon m -> {
        out.writeByte(MUT_PUT_NODE_CORDON);
        out.writeUTF(m.nodeId());
        out.writeBoolean(m.cordoned());
      }
      case StateMutation.AppendInstanceEvent m -> {
        out.writeByte(MUT_APPEND_INSTANCE_EVENT);
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
        out.writeUTF(m.name());
      }
      case StateMutation.PutJobRun m -> {
        out.writeByte(MUT_PUT_JOB_RUN);
        DomainCodec.writeJobRun(out, m.run());
      }
      case StateMutation.RemoveJobRun m -> {
        out.writeByte(MUT_REMOVE_JOB_RUN);
        out.writeUTF(m.jobName());
        out.writeInt(m.attempt());
      }
      case StateMutation.PutJobPhase m -> {
        out.writeByte(MUT_PUT_JOB_PHASE);
        out.writeUTF(m.jobName());
        out.writeUTF(m.phase().name());
      }
      case StateMutation.PutCronJobSpec m -> {
        out.writeByte(MUT_PUT_CRONJOB_SPEC);
        DomainCodec.writeCronJobSpec(out, m.spec());
      }
      case StateMutation.RemoveCronJobSpec m -> {
        out.writeByte(MUT_REMOVE_CRONJOB_SPEC);
        out.writeUTF(m.name());
      }
      case StateMutation.PutCronJobLastSchedule m -> {
        out.writeByte(MUT_PUT_CRONJOB_LAST_SCHEDULE);
        out.writeUTF(m.name());
        out.writeLong(m.lastScheduleTime().toEpochMilli());
      }
      case StateMutation.PutDaemonSetSpec m -> {
        out.writeByte(MUT_PUT_DAEMONSET_SPEC);
        DomainCodec.writeDaemonSetSpec(out, m.spec());
      }
      case StateMutation.RemoveDaemonSetSpec m -> {
        out.writeByte(MUT_REMOVE_DAEMONSET_SPEC);
        out.writeUTF(m.name());
      }
      case StateMutation.PutDaemonSetAssignment m -> {
        out.writeByte(MUT_PUT_DAEMONSET_ASSIGNMENT);
        DomainCodec.writeDaemonSetAssignment(out, m.assignment());
      }
      case StateMutation.RemoveDaemonSetAssignment m -> {
        out.writeByte(MUT_REMOVE_DAEMONSET_ASSIGNMENT);
        out.writeUTF(m.daemonSetName());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.PutRollingDaemonSetNode m -> {
        out.writeByte(MUT_PUT_ROLLING_DAEMONSET_NODE);
        out.writeUTF(m.daemonSetName());
        out.writeUTF(m.nodeId());
      }
      case StateMutation.ClearRollingDaemonSetNode m -> {
        out.writeByte(MUT_CLEAR_ROLLING_DAEMONSET_NODE);
        out.writeUTF(m.daemonSetName());
      }
    }
  }

  private static StateMutation readStateMutation(DataInputStream in) throws IOException {
    byte tag = in.readByte();
    return switch (tag) {
      case MUT_PUT_DEPLOYMENT ->
          new StateMutation.PutDeployment(DomainCodec.readDeploymentSpec(in));
      case MUT_REMOVE_DEPLOYMENT -> new StateMutation.RemoveDeployment(in.readUTF());
      case MUT_PUT_ASSIGNMENT ->
          new StateMutation.PutAssignment(DomainCodec.readInstanceAssignment(in));
      case MUT_REMOVE_ASSIGNMENT -> new StateMutation.RemoveAssignment(in.readUTF(), in.readInt());
      case MUT_PUT_ROLLING_INDEX -> new StateMutation.PutRollingIndex(in.readUTF(), in.readInt());
      case MUT_CLEAR_ROLLING_INDEX -> new StateMutation.ClearRollingIndex(in.readUTF());
      case MUT_PUT_EFFECTIVE_REPLICAS ->
          new StateMutation.PutEffectiveReplicas(in.readUTF(), in.readInt());
      case MUT_PUT_NODE_REGISTRATION ->
          new StateMutation.PutNodeRegistration(DomainCodec.readNodeRegistration(in));
      case MUT_PUT_TENANT -> new StateMutation.PutTenant(DomainCodec.readTenant(in));
      case MUT_REMOVE_TENANT -> new StateMutation.RemoveTenant(in.readUTF());
      case MUT_PUT_QUOTA_VIOLATION ->
          new StateMutation.PutQuotaViolation(in.readUTF(), in.readBoolean());
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
      case MUT_REMOVE_RECONCILER_INSTANCE_STATE ->
          new StateMutation.RemoveReconcilerInstanceState(in.readUTF(), in.readInt());
      case MUT_PUT_NODE_CORDON -> new StateMutation.PutNodeCordon(in.readUTF(), in.readBoolean());
      case MUT_APPEND_INSTANCE_EVENT ->
          new StateMutation.AppendInstanceEvent(DomainCodec.readInstanceEvent(in));
      case MUT_APPEND_AUDIT_EVENT ->
          new StateMutation.AppendAuditEvent(DomainCodec.readAuditEvent(in));
      case MUT_PUT_JOB_SPEC -> new StateMutation.PutJobSpec(DomainCodec.readJobSpec(in));
      case MUT_REMOVE_JOB_SPEC -> new StateMutation.RemoveJobSpec(in.readUTF());
      case MUT_PUT_JOB_RUN -> new StateMutation.PutJobRun(DomainCodec.readJobRun(in));
      case MUT_REMOVE_JOB_RUN -> new StateMutation.RemoveJobRun(in.readUTF(), in.readInt());
      case MUT_PUT_JOB_PHASE ->
          new StateMutation.PutJobPhase(in.readUTF(), JobPhase.valueOf(in.readUTF()));
      case MUT_PUT_CRONJOB_SPEC ->
          new StateMutation.PutCronJobSpec(DomainCodec.readCronJobSpec(in));
      case MUT_REMOVE_CRONJOB_SPEC -> new StateMutation.RemoveCronJobSpec(in.readUTF());
      case MUT_PUT_CRONJOB_LAST_SCHEDULE ->
          new StateMutation.PutCronJobLastSchedule(
              in.readUTF(), Instant.ofEpochMilli(in.readLong()));
      case MUT_PUT_DAEMONSET_SPEC ->
          new StateMutation.PutDaemonSetSpec(DomainCodec.readDaemonSetSpec(in));
      case MUT_REMOVE_DAEMONSET_SPEC -> new StateMutation.RemoveDaemonSetSpec(in.readUTF());
      case MUT_PUT_DAEMONSET_ASSIGNMENT ->
          new StateMutation.PutDaemonSetAssignment(DomainCodec.readDaemonSetAssignment(in));
      case MUT_REMOVE_DAEMONSET_ASSIGNMENT ->
          new StateMutation.RemoveDaemonSetAssignment(in.readUTF(), in.readUTF());
      case MUT_PUT_ROLLING_DAEMONSET_NODE ->
          new StateMutation.PutRollingDaemonSetNode(in.readUTF(), in.readUTF());
      case MUT_CLEAR_ROLLING_DAEMONSET_NODE ->
          new StateMutation.ClearRollingDaemonSetNode(in.readUTF());
      default -> throw new IllegalArgumentException("unknown StateMutation tag: " + tag);
    };
  }

  // ---- StateSnapshot: standalone byte[] payload, not RaftRpc-framed ----

  public static byte[] encodeSnapshot(StateSnapshot snapshot) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeInt(snapshot.deployments().size());
      for (DeploymentSpec spec : snapshot.deployments()) {
        DomainCodec.writeDeploymentSpec(out, spec);
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
      for (Map.Entry<String, String> e : snapshot.rollingDaemonSetNodes().entrySet()) {
        out.writeUTF(e.getKey());
        out.writeUTF(e.getValue());
      }
      out.writeInt(snapshot.nodeRegistrations().size());
      for (NodeRegistration registration : snapshot.nodeRegistrations()) {
        DomainCodec.writeNodeRegistration(out, registration);
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
      for (InstanceEvent event : snapshot.instanceEvents()) {
        DomainCodec.writeInstanceEvent(out, event);
      }
      out.writeInt(snapshot.auditEvents().size());
      for (AuditEvent event : snapshot.auditEvents()) {
        DomainCodec.writeAuditEvent(out, event);
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
        deployments.add(DomainCodec.readDeploymentSpec(in));
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
      Map<String, String> rollingDaemonSetNodes = new LinkedHashMap<>();
      int rollingDaemonSetNodeCount = in.readInt();
      for (int i = 0; i < rollingDaemonSetNodeCount; i++) {
        rollingDaemonSetNodes.put(in.readUTF(), in.readUTF());
      }
      List<NodeRegistration> registrations = new ArrayList<>();
      int registrationCount = in.readInt();
      for (int i = 0; i < registrationCount; i++) {
        registrations.add(DomainCodec.readNodeRegistration(in));
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
      List<InstanceEvent> instanceEvents = new ArrayList<>();
      int instanceEventCount = in.readInt();
      for (int i = 0; i < instanceEventCount; i++) {
        instanceEvents.add(DomainCodec.readInstanceEvent(in));
      }
      List<AuditEvent> auditEvents = new ArrayList<>();
      int auditEventCount = in.readInt();
      for (int i = 0; i < auditEventCount; i++) {
        auditEvents.add(DomainCodec.readAuditEvent(in));
      }
      return new StateSnapshot(
          deployments,
          assignments,
          jobSpecs,
          jobRuns,
          jobPhases,
          cronJobSpecs,
          cronJobLastSchedule,
          daemonSetSpecs,
          daemonSetAssignments,
          rollingDaemonSetNodes,
          registrations,
          rollingIndices,
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
          auditEvents);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
