package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.tenant.Tenant;
import com.gimle.mimir.codec.DomainCodec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.raft.RaftCodec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ReconcilerInstanceState;
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
import java.util.List;

/**
 * Encodes/decodes a {@link StoreRpc} the same length-prefix-plus-tag-byte shape {@link RaftCodec}
 * uses for {@code RaftRpc} -- deliberately not sharing transport-level code with {@code RaftCodec}
 * (design doc §4.3, deferred), but both delegate domain-type (de)serialization to {@link
 * DomainCodec} so {@code DeploymentSpec}/{@code InstanceAssignment}/RBAC/etc. are encoded exactly
 * one way across the whole module. {@code StateMutation} payloads inside a {@link StoreRpc.Propose}
 * reuse {@link RaftCodec#encodeLogEntryMutation}/{@link RaftCodec#decodeLogEntryMutation} rather
 * than a third copy of {@code StateMutation}'s own 18-variant switch.
 */
public final class StoreCodec {

  // ---- requests ----
  private static final byte TAG_PROPOSE = 0;
  private static final byte TAG_PUT_HEARTBEAT = 1;
  private static final byte TAG_ACQUIRE_OR_RENEW_LEASE = 2;
  private static final byte TAG_RELEASE_LEASE = 3;
  private static final byte TAG_LIST_ACCOUNTS = 4;
  private static final byte TAG_GET_TENANT = 5;
  private static final byte TAG_GET_DEPLOYMENT = 6;
  private static final byte TAG_LIST_DEPLOYMENTS = 7;
  private static final byte TAG_LIST_ASSIGNMENTS_FOR = 8;
  private static final byte TAG_IS_QUOTA_VIOLATING = 9;
  private static final byte TAG_LIST_ASSIGNMENTS = 10;
  private static final byte TAG_LIST_NODE_REGISTRATIONS = 11;
  private static final byte TAG_LIST_TENANTS = 12;
  private static final byte TAG_LIST_CONFIG_ENTRIES_FOR = 13;
  private static final byte TAG_LIST_ROLES = 14;
  private static final byte TAG_GET_ROLE = 15;
  private static final byte TAG_LIST_ROLE_BINDINGS = 16;
  private static final byte TAG_GET_ROLE_BINDING = 17;
  private static final byte TAG_GET_ACCOUNT = 18;
  private static final byte TAG_GET_NODE_REGISTRATION = 19;
  private static final byte TAG_GET_EFFECTIVE_REPLICAS = 20;
  private static final byte TAG_GET_ROLLING_INDEX = 21;
  private static final byte TAG_GET_NODE_HEARTBEAT = 22;
  private static final byte TAG_GET_RECONCILER_INSTANCE_STATE = 43;
  private static final byte TAG_LIST_RECONCILER_INSTANCE_STATES = 45;

  // ---- responses ----
  private static final byte TAG_OK = 23;
  private static final byte TAG_NOT_LEADER = 24;
  private static final byte TAG_LEASE_RESULT = 25;
  private static final byte TAG_BOOL_RESULT = 26;
  private static final byte TAG_INT_RESULT = 27;
  private static final byte TAG_DEPLOYMENT_RESULT = 28;
  private static final byte TAG_TENANT_RESULT = 29;
  private static final byte TAG_ROLE_RESULT = 30;
  private static final byte TAG_ROLE_BINDING_RESULT = 31;
  private static final byte TAG_ACCOUNT_RESULT = 32;
  private static final byte TAG_NODE_REGISTRATION_RESULT = 33;
  private static final byte TAG_HEARTBEAT_RESULT = 34;
  private static final byte TAG_ACCOUNT_LIST_RESULT = 35;
  private static final byte TAG_DEPLOYMENT_LIST_RESULT = 36;
  private static final byte TAG_ASSIGNMENT_LIST_RESULT = 37;
  private static final byte TAG_NODE_REGISTRATION_LIST_RESULT = 38;
  private static final byte TAG_TENANT_LIST_RESULT = 39;
  private static final byte TAG_CONFIG_ENTRY_LIST_RESULT = 40;
  private static final byte TAG_ROLE_LIST_RESULT = 41;
  private static final byte TAG_ROLE_BINDING_LIST_RESULT = 42;
  private static final byte TAG_RECONCILER_INSTANCE_STATE_RESULT = 44;
  private static final byte TAG_RECONCILER_INSTANCE_STATE_LIST_RESULT = 46;

  /** Same bound {@link RaftCodec} uses; a {@code StoreRpc} frame is never larger in practice. */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private StoreCodec() {}

  private static void checkFrameLength(int length) {
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
  }

  public static void write(OutputStream out, StoreRpc rpc) throws IOException {
    byte[] body = encodeBody(rpc);
    DataOutputStream data = new DataOutputStream(out);
    data.writeInt(body.length);
    data.write(body);
    data.flush();
  }

  /**
   * Returns {@code null} at a clean end-of-stream, matching {@link RaftCodec#read}'s convention.
   */
  public static StoreRpc read(InputStream in) throws IOException {
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
    return decodeBody(body);
  }

  private static byte[] encodeBody(StoreRpc rpc) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      switch (rpc) {
        case StoreRpc.Propose v -> {
          out.writeByte(TAG_PROPOSE);
          DomainCodec.writeBytes(out, RaftCodec.encodeMutation(v.mutation()));
        }
        case StoreRpc.PutHeartbeat v -> {
          out.writeByte(TAG_PUT_HEARTBEAT);
          DomainCodec.writeNodeHeartbeat(out, v.heartbeat());
        }
        case StoreRpc.AcquireOrRenewLease v -> {
          out.writeByte(TAG_ACQUIRE_OR_RENEW_LEASE);
          out.writeUTF(v.name());
          out.writeUTF(v.holderId());
          out.writeLong(v.ttlMillis());
        }
        case StoreRpc.ReleaseLease v -> {
          out.writeByte(TAG_RELEASE_LEASE);
          out.writeUTF(v.name());
          out.writeUTF(v.holderId());
        }
        case StoreRpc.ListAccounts v -> out.writeByte(TAG_LIST_ACCOUNTS);
        case StoreRpc.GetTenant v -> {
          out.writeByte(TAG_GET_TENANT);
          out.writeUTF(v.id());
        }
        case StoreRpc.GetDeployment v -> {
          out.writeByte(TAG_GET_DEPLOYMENT);
          out.writeUTF(v.name());
        }
        case StoreRpc.ListDeployments v -> out.writeByte(TAG_LIST_DEPLOYMENTS);
        case StoreRpc.ListAssignmentsFor v -> {
          out.writeByte(TAG_LIST_ASSIGNMENTS_FOR);
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.IsQuotaViolating v -> {
          out.writeByte(TAG_IS_QUOTA_VIOLATING);
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.ListAssignments v -> out.writeByte(TAG_LIST_ASSIGNMENTS);
        case StoreRpc.ListNodeRegistrations v -> out.writeByte(TAG_LIST_NODE_REGISTRATIONS);
        case StoreRpc.ListTenants v -> out.writeByte(TAG_LIST_TENANTS);
        case StoreRpc.ListConfigEntriesFor v -> {
          out.writeByte(TAG_LIST_CONFIG_ENTRIES_FOR);
          out.writeUTF(v.tenantId());
        }
        case StoreRpc.ListRoles v -> out.writeByte(TAG_LIST_ROLES);
        case StoreRpc.GetRole v -> {
          out.writeByte(TAG_GET_ROLE);
          out.writeUTF(v.name());
        }
        case StoreRpc.ListRoleBindings v -> out.writeByte(TAG_LIST_ROLE_BINDINGS);
        case StoreRpc.GetRoleBinding v -> {
          out.writeByte(TAG_GET_ROLE_BINDING);
          out.writeUTF(v.id());
        }
        case StoreRpc.GetAccount v -> {
          out.writeByte(TAG_GET_ACCOUNT);
          out.writeUTF(v.username());
        }
        case StoreRpc.GetNodeRegistration v -> {
          out.writeByte(TAG_GET_NODE_REGISTRATION);
          out.writeUTF(v.nodeId());
        }
        case StoreRpc.GetEffectiveReplicas v -> {
          out.writeByte(TAG_GET_EFFECTIVE_REPLICAS);
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetRollingIndex v -> {
          out.writeByte(TAG_GET_ROLLING_INDEX);
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetNodeHeartbeat v -> {
          out.writeByte(TAG_GET_NODE_HEARTBEAT);
          out.writeUTF(v.nodeId());
        }
        case StoreRpc.GetReconcilerInstanceState v -> {
          out.writeByte(TAG_GET_RECONCILER_INSTANCE_STATE);
          out.writeUTF(v.deploymentName());
          out.writeInt(v.instanceIndex());
        }
        case StoreRpc.ListReconcilerInstanceStates v ->
            out.writeByte(TAG_LIST_RECONCILER_INSTANCE_STATES);
        case StoreRpc.Ok v -> out.writeByte(TAG_OK);
        case StoreRpc.NotLeader v -> {
          out.writeByte(TAG_NOT_LEADER);
          out.writeUTF(v.leaderClientAddress());
        }
        case StoreRpc.LeaseResult v -> {
          out.writeByte(TAG_LEASE_RESULT);
          out.writeBoolean(v.granted());
          out.writeUTF(v.holderId());
          out.writeLong(v.expiresAtEpochMilli());
        }
        case StoreRpc.BoolResult v -> {
          out.writeByte(TAG_BOOL_RESULT);
          out.writeBoolean(v.value());
        }
        case StoreRpc.IntResult v -> {
          out.writeByte(TAG_INT_RESULT);
          out.writeBoolean(v.present());
          out.writeInt(v.value());
        }
        case StoreRpc.DeploymentResult v -> {
          out.writeByte(TAG_DEPLOYMENT_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeDeploymentSpec(out, v.value());
          }
        }
        case StoreRpc.TenantResult v -> {
          out.writeByte(TAG_TENANT_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeTenant(out, v.value());
          }
        }
        case StoreRpc.RoleResult v -> {
          out.writeByte(TAG_ROLE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeRole(out, v.value());
          }
        }
        case StoreRpc.RoleBindingResult v -> {
          out.writeByte(TAG_ROLE_BINDING_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeRoleBinding(out, v.value());
          }
        }
        case StoreRpc.AccountResult v -> {
          out.writeByte(TAG_ACCOUNT_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeAccount(out, v.value());
          }
        }
        case StoreRpc.NodeRegistrationResult v -> {
          out.writeByte(TAG_NODE_REGISTRATION_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeNodeRegistration(out, v.value());
          }
        }
        case StoreRpc.HeartbeatResult v -> {
          out.writeByte(TAG_HEARTBEAT_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeObservedHeartbeat(out, v.value());
          }
        }
        case StoreRpc.AccountListResult v -> {
          out.writeByte(TAG_ACCOUNT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (Account a : v.values()) {
            DomainCodec.writeAccount(out, a);
          }
        }
        case StoreRpc.DeploymentListResult v -> {
          out.writeByte(TAG_DEPLOYMENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (DeploymentSpec d : v.values()) {
            DomainCodec.writeDeploymentSpec(out, d);
          }
        }
        case StoreRpc.AssignmentListResult v -> {
          out.writeByte(TAG_ASSIGNMENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (InstanceAssignment a : v.values()) {
            DomainCodec.writeInstanceAssignment(out, a);
          }
        }
        case StoreRpc.NodeRegistrationListResult v -> {
          out.writeByte(TAG_NODE_REGISTRATION_LIST_RESULT);
          out.writeInt(v.values().size());
          for (NodeRegistration r : v.values()) {
            DomainCodec.writeNodeRegistration(out, r);
          }
        }
        case StoreRpc.TenantListResult v -> {
          out.writeByte(TAG_TENANT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (Tenant t : v.values()) {
            DomainCodec.writeTenant(out, t);
          }
        }
        case StoreRpc.ConfigEntryListResult v -> {
          out.writeByte(TAG_CONFIG_ENTRY_LIST_RESULT);
          out.writeInt(v.values().size());
          for (ConfigEntry c : v.values()) {
            DomainCodec.writeConfigEntry(out, c);
          }
        }
        case StoreRpc.RoleListResult v -> {
          out.writeByte(TAG_ROLE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (com.gimle.core.authz.Role r : v.values()) {
            DomainCodec.writeRole(out, r);
          }
        }
        case StoreRpc.RoleBindingListResult v -> {
          out.writeByte(TAG_ROLE_BINDING_LIST_RESULT);
          out.writeInt(v.values().size());
          for (RoleBinding b : v.values()) {
            DomainCodec.writeRoleBinding(out, b);
          }
        }
        case StoreRpc.ReconcilerInstanceStateResult v -> {
          out.writeByte(TAG_RECONCILER_INSTANCE_STATE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeReconcilerInstanceState(out, v.value());
          }
        }
        case StoreRpc.ReconcilerInstanceStateListResult v -> {
          out.writeByte(TAG_RECONCILER_INSTANCE_STATE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (ReconcilerInstanceState s : v.values()) {
            DomainCodec.writeReconcilerInstanceState(out, s);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static StoreRpc decodeBody(byte[] body) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      byte tag = in.readByte();
      return switch (tag) {
        case TAG_PROPOSE ->
            new StoreRpc.Propose(RaftCodec.decodeMutation(DomainCodec.readBytes(in)));
        case TAG_PUT_HEARTBEAT -> new StoreRpc.PutHeartbeat(DomainCodec.readNodeHeartbeat(in));
        case TAG_ACQUIRE_OR_RENEW_LEASE ->
            new StoreRpc.AcquireOrRenewLease(in.readUTF(), in.readUTF(), in.readLong());
        case TAG_RELEASE_LEASE -> new StoreRpc.ReleaseLease(in.readUTF(), in.readUTF());
        case TAG_LIST_ACCOUNTS -> new StoreRpc.ListAccounts();
        case TAG_GET_TENANT -> new StoreRpc.GetTenant(in.readUTF());
        case TAG_GET_DEPLOYMENT -> new StoreRpc.GetDeployment(in.readUTF());
        case TAG_LIST_DEPLOYMENTS -> new StoreRpc.ListDeployments();
        case TAG_LIST_ASSIGNMENTS_FOR -> new StoreRpc.ListAssignmentsFor(in.readUTF());
        case TAG_IS_QUOTA_VIOLATING -> new StoreRpc.IsQuotaViolating(in.readUTF());
        case TAG_LIST_ASSIGNMENTS -> new StoreRpc.ListAssignments();
        case TAG_LIST_NODE_REGISTRATIONS -> new StoreRpc.ListNodeRegistrations();
        case TAG_LIST_TENANTS -> new StoreRpc.ListTenants();
        case TAG_LIST_CONFIG_ENTRIES_FOR -> new StoreRpc.ListConfigEntriesFor(in.readUTF());
        case TAG_LIST_ROLES -> new StoreRpc.ListRoles();
        case TAG_GET_ROLE -> new StoreRpc.GetRole(in.readUTF());
        case TAG_LIST_ROLE_BINDINGS -> new StoreRpc.ListRoleBindings();
        case TAG_GET_ROLE_BINDING -> new StoreRpc.GetRoleBinding(in.readUTF());
        case TAG_GET_ACCOUNT -> new StoreRpc.GetAccount(in.readUTF());
        case TAG_GET_NODE_REGISTRATION -> new StoreRpc.GetNodeRegistration(in.readUTF());
        case TAG_GET_EFFECTIVE_REPLICAS -> new StoreRpc.GetEffectiveReplicas(in.readUTF());
        case TAG_GET_ROLLING_INDEX -> new StoreRpc.GetRollingIndex(in.readUTF());
        case TAG_GET_NODE_HEARTBEAT -> new StoreRpc.GetNodeHeartbeat(in.readUTF());
        case TAG_GET_RECONCILER_INSTANCE_STATE ->
            new StoreRpc.GetReconcilerInstanceState(in.readUTF(), in.readInt());
        case TAG_LIST_RECONCILER_INSTANCE_STATES -> new StoreRpc.ListReconcilerInstanceStates();
        case TAG_OK -> new StoreRpc.Ok();
        case TAG_NOT_LEADER -> new StoreRpc.NotLeader(in.readUTF());
        case TAG_LEASE_RESULT ->
            new StoreRpc.LeaseResult(in.readBoolean(), in.readUTF(), in.readLong());
        case TAG_BOOL_RESULT -> new StoreRpc.BoolResult(in.readBoolean());
        case TAG_INT_RESULT -> new StoreRpc.IntResult(in.readBoolean(), in.readInt());
        case TAG_DEPLOYMENT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.DeploymentResult(
              present, present ? DomainCodec.readDeploymentSpec(in) : null);
        }
        case TAG_TENANT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.TenantResult(present, present ? DomainCodec.readTenant(in) : null);
        }
        case TAG_ROLE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.RoleResult(present, present ? DomainCodec.readRole(in) : null);
        }
        case TAG_ROLE_BINDING_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.RoleBindingResult(
              present, present ? DomainCodec.readRoleBinding(in) : null);
        }
        case TAG_ACCOUNT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.AccountResult(present, present ? DomainCodec.readAccount(in) : null);
        }
        case TAG_NODE_REGISTRATION_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.NodeRegistrationResult(
              present, present ? DomainCodec.readNodeRegistration(in) : null);
        }
        case TAG_HEARTBEAT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.HeartbeatResult(
              present, present ? DomainCodec.readObservedHeartbeat(in) : null);
        }
        case TAG_ACCOUNT_LIST_RESULT -> {
          int count = in.readInt();
          List<Account> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readAccount(in));
          }
          yield new StoreRpc.AccountListResult(values);
        }
        case TAG_DEPLOYMENT_LIST_RESULT -> {
          int count = in.readInt();
          List<DeploymentSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readDeploymentSpec(in));
          }
          yield new StoreRpc.DeploymentListResult(values);
        }
        case TAG_ASSIGNMENT_LIST_RESULT -> {
          int count = in.readInt();
          List<InstanceAssignment> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readInstanceAssignment(in));
          }
          yield new StoreRpc.AssignmentListResult(values);
        }
        case TAG_NODE_REGISTRATION_LIST_RESULT -> {
          int count = in.readInt();
          List<NodeRegistration> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readNodeRegistration(in));
          }
          yield new StoreRpc.NodeRegistrationListResult(values);
        }
        case TAG_TENANT_LIST_RESULT -> {
          int count = in.readInt();
          List<Tenant> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readTenant(in));
          }
          yield new StoreRpc.TenantListResult(values);
        }
        case TAG_CONFIG_ENTRY_LIST_RESULT -> {
          int count = in.readInt();
          List<ConfigEntry> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readConfigEntry(in));
          }
          yield new StoreRpc.ConfigEntryListResult(values);
        }
        case TAG_ROLE_LIST_RESULT -> {
          int count = in.readInt();
          List<com.gimle.core.authz.Role> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readRole(in));
          }
          yield new StoreRpc.RoleListResult(values);
        }
        case TAG_ROLE_BINDING_LIST_RESULT -> {
          int count = in.readInt();
          List<RoleBinding> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readRoleBinding(in));
          }
          yield new StoreRpc.RoleBindingListResult(values);
        }
        case TAG_RECONCILER_INSTANCE_STATE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.ReconcilerInstanceStateResult(
              present, present ? DomainCodec.readReconcilerInstanceState(in) : null);
        }
        case TAG_RECONCILER_INSTANCE_STATE_LIST_RESULT -> {
          int count = in.readInt();
          List<ReconcilerInstanceState> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readReconcilerInstanceState(in));
          }
          yield new StoreRpc.ReconcilerInstanceStateListResult(values);
        }
        default -> throw new IllegalArgumentException("unknown StoreRpc tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
