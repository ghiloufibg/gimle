package com.gimle.mimir.rpc;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Role;
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
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.IngressSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.raft.RaftCodec;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobPhase;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadHealthState;
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
import java.util.Optional;

/**
 * Encodes/decodes a {@link StoreRpc} the same length-prefix-plus-version-plus-tag-byte shape {@link
 * RaftCodec} uses for {@code RaftRpc} -- deliberately not sharing transport-level code with {@code
 * RaftCodec}, since the two wire formats serve different peers and are free to diverge, but both
 * delegate domain-type (de)serialization to {@link DomainCodec} so {@code DeploymentSpec}/{@code
 * InstanceAssignment}/RBAC/etc. are encoded exactly one way across the whole module. {@code
 * StateMutation} payloads inside a {@link StoreRpc.Propose} reuse {@link
 * RaftCodec#encodeLogEntryMutation}/{@link RaftCodec#decodeLogEntryMutation} rather than a third
 * copy of {@code StateMutation}'s own 18-variant switch.
 *
 * <p>The version byte is checked before any version-specific field is decoded, exactly the way
 * {@code RaftCodec}/{@code FabricCodec} check their own: a client either understands {@link
 * #CURRENT_VERSION} or the RPC is rejected outright rather than misdecoded -- what actually matters
 * while a control-plane replica on one binary version and a store replica on another are both live
 * during a rolling upgrade.
 */
public final class StoreCodec {

  /**
   * The only wire-protocol version any writer produces today; bump this when {@link StoreRpc}'s own
   * encoding shape changes.
   */
  private static final int CURRENT_VERSION = 1;

  // ---- requests ----
  private static final byte TAG_PROPOSE = 0;
  private static final byte TAG_PUT_HEARTBEAT = 1;
  private static final byte TAG_ACQUIRE_OR_RENEW_LEASE = 2;
  private static final byte TAG_RELEASE_LEASE = 3;
  private static final byte TAG_LIST_ACCOUNTS = 4;
  private static final byte TAG_GET_TENANT = 5;
  private static final byte TAG_GET_DEPLOYMENT = 6;
  private static final byte TAG_LIST_DEPLOYMENTS = 7;
  private static final byte TAG_GET_DEPLOYMENT_GENERATION = 118;
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
  private static final byte TAG_LIST_ROLLING_INDICES = 21;
  private static final byte TAG_GET_NODE_HEARTBEAT = 22;
  private static final byte TAG_GET_RECONCILER_INSTANCE_STATE = 43;
  private static final byte TAG_LIST_RECONCILER_INSTANCE_STATES = 45;
  private static final byte TAG_IS_NODE_CORDONED = 47;
  private static final byte TAG_LIST_INSTANCE_EVENTS = 48;
  private static final byte TAG_ADD_SERVER = 50;
  private static final byte TAG_REMOVE_SERVER = 51;
  private static final byte TAG_LIST_AUDIT_EVENTS = 52;
  private static final byte TAG_GET_JOB_SPEC = 54;
  private static final byte TAG_LIST_JOB_SPECS = 55;
  private static final byte TAG_LIST_JOB_RUNS_FOR = 56;
  private static final byte TAG_LIST_JOB_RUNS = 57;
  private static final byte TAG_GET_JOB_PHASE = 58;
  private static final byte TAG_GET_CRONJOB_SPEC = 63;
  private static final byte TAG_LIST_CRONJOB_SPECS = 64;
  private static final byte TAG_GET_CRONJOB_LAST_SCHEDULE = 65;
  private static final byte TAG_GET_DAEMONSET_SPEC = 69;
  private static final byte TAG_LIST_DAEMONSET_SPECS = 70;
  private static final byte TAG_LIST_DAEMONSET_ASSIGNMENTS = 71;
  private static final byte TAG_LIST_DAEMONSET_ASSIGNMENTS_FOR = 72;
  private static final byte TAG_LIST_ROLLING_DAEMONSET_NODES = 73;
  private static final byte TAG_GET_STATEFULSET_SPEC = 78;
  private static final byte TAG_LIST_STATEFULSET_SPECS = 79;
  private static final byte TAG_LIST_STATEFULSET_ASSIGNMENTS = 80;
  private static final byte TAG_LIST_STATEFULSET_ASSIGNMENTS_FOR = 81;
  private static final byte TAG_GET_ROLLING_STATEFULSET_INDEX = 82;
  private static final byte TAG_GET_STATEFULSET_INDEX_NODE = 83;
  private static final byte TAG_GET_JOB_RUN_SUMMARY = 116;

  // ---- responses ----
  private static final byte TAG_OK = 23;
  private static final byte TAG_NOT_LEADER = 24;
  private static final byte TAG_MUTATION_REJECTED = 119;
  private static final byte TAG_LEASE_RESULT = 25;
  private static final byte TAG_BOOL_RESULT = 26;
  private static final byte TAG_INT_RESULT = 27;
  private static final byte TAG_DEPLOYMENT_RESULT = 28;
  private static final byte TAG_GENERATION_RESULT = 120;
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
  private static final byte TAG_INSTANCE_EVENT_LIST_RESULT = 49;
  private static final byte TAG_AUDIT_EVENT_LIST_RESULT = 53;
  private static final byte TAG_JOB_SPEC_RESULT = 59;
  private static final byte TAG_JOB_SPEC_LIST_RESULT = 60;
  private static final byte TAG_JOB_RUN_LIST_RESULT = 61;
  private static final byte TAG_JOB_PHASE_RESULT = 62;
  private static final byte TAG_CRONJOB_SPEC_RESULT = 66;
  private static final byte TAG_CRONJOB_SPEC_LIST_RESULT = 67;
  private static final byte TAG_INSTANT_RESULT = 68;
  private static final byte TAG_DAEMONSET_SPEC_RESULT = 74;
  private static final byte TAG_DAEMONSET_SPEC_LIST_RESULT = 75;
  private static final byte TAG_DAEMONSET_ASSIGNMENT_LIST_RESULT = 76;
  private static final byte TAG_STRING_RESULT = 77;
  private static final byte TAG_STATEFULSET_SPEC_RESULT = 84;
  private static final byte TAG_STATEFULSET_SPEC_LIST_RESULT = 85;
  private static final byte TAG_STATEFULSET_ASSIGNMENT_LIST_RESULT = 86;
  private static final byte TAG_INT_SET_RESULT = 87;
  private static final byte TAG_STRING_SET_RESULT = 88;
  private static final byte TAG_LIST_SURGE_INDICES = 89;
  private static final byte TAG_INT_INT_MAP_RESULT = 90;
  private static final byte TAG_STATUS = 91;
  private static final byte TAG_STATUS_RESULT = 92;
  private static final byte TAG_LIST_CONFIG_ENTRIES_FOR_LINEARIZABLE = 93;
  private static final byte TAG_GET_SERVICE = 94;
  private static final byte TAG_LIST_SERVICES = 95;
  private static final byte TAG_SERVICE_RESULT = 96;
  private static final byte TAG_SERVICE_LIST_RESULT = 97;
  private static final byte TAG_GET_NETWORK_POLICY = 98;
  private static final byte TAG_LIST_NETWORK_POLICIES = 99;
  private static final byte TAG_NETWORK_POLICY_RESULT = 100;
  private static final byte TAG_NETWORK_POLICY_LIST_RESULT = 101;
  private static final byte TAG_LIST_CONTROLLER_REVISIONS = 102;
  private static final byte TAG_GET_CONTROLLER_REVISION = 103;
  private static final byte TAG_CONTROLLER_REVISION_LIST_RESULT = 104;
  private static final byte TAG_CONTROLLER_REVISION_RESULT = 105;
  private static final byte TAG_GET_LIMIT_RANGE = 106;
  private static final byte TAG_LIST_LIMIT_RANGES = 107;
  private static final byte TAG_LIMIT_RANGE_RESULT = 108;
  private static final byte TAG_LIMIT_RANGE_LIST_RESULT = 109;
  private static final byte TAG_IS_LIMIT_RANGE_VIOLATING = 110;
  private static final byte TAG_GET_LIMIT_RANGE_VIOLATION_REASON = 111;
  private static final byte TAG_IS_CERTIFICATE_REVOKED = 112;
  private static final byte TAG_LIST_REVOKED_CERTIFICATE_SERIALS = 113;
  private static final byte TAG_GET_WORKLOAD_TOKEN = 114;
  private static final byte TAG_WORKLOAD_TOKEN_RESULT = 115;
  private static final byte TAG_JOB_RUN_SUMMARY_RESULT = 117;
  private static final byte TAG_GET_NODE_TAINTS = 121;
  // ---- custom kinds (Galdr) ----
  // 122-127 exhaust the signed byte's non-negative range; the tag space then continues into its
  // negative half (readByte is signed, so -128..-1 are 128 more perfectly valid tag values).
  private static final byte TAG_LIST_KIND_DEFINITIONS = 122;
  private static final byte TAG_GET_KIND_DEFINITION = 123;
  private static final byte TAG_LIST_CUSTOM_RESOURCES = 124;
  private static final byte TAG_LIST_CUSTOM_RESOURCES_FOR = 125;
  private static final byte TAG_GET_CUSTOM_RESOURCE = 126;
  private static final byte TAG_KIND_DEFINITION_RESULT = 127;
  private static final byte TAG_KIND_DEFINITION_LIST_RESULT = -128;
  private static final byte TAG_CUSTOM_RESOURCE_RESULT = -127;
  private static final byte TAG_CUSTOM_RESOURCE_LIST_RESULT = -126;
  private static final byte TAG_GET_WORKLOAD_HEALTH_STATE = -125;
  private static final byte TAG_WORKLOAD_HEALTH_STATE_RESULT = -124;
  private static final byte TAG_LIST_WORKLOAD_HEALTH_STATES = -123;
  private static final byte TAG_WORKLOAD_HEALTH_STATE_LIST_RESULT = -122;
  private static final byte TAG_GET_SESSION_REVOKED_BEFORE_EPOCH_MILLI = -121;
  private static final byte TAG_GET_SNAPSHOT = -120;
  private static final byte TAG_SNAPSHOT_RESULT = -119;
  private static final byte TAG_GET_AUDIT_TRAIL_STATUS = -118;
  private static final byte TAG_AUDIT_TRAIL_STATUS_RESULT = -117;
  private static final byte TAG_GET_ALERT_RULE = -116;
  private static final byte TAG_LIST_ALERT_RULES = -115;
  private static final byte TAG_ALERT_RULE_RESULT = -114;
  private static final byte TAG_ALERT_RULE_LIST_RESULT = -113;
  private static final byte TAG_GET_DEPLOYMENT_LAST_SCALE = -112;
  private static final byte TAG_GET_INGRESS = -111;
  private static final byte TAG_LIST_INGRESSES = -110;
  private static final byte TAG_INGRESS_RESULT = -109;
  private static final byte TAG_INGRESS_LIST_RESULT = -108;
  private static final byte TAG_LIST_ALL_INSTANCE_EVENTS = -107;

  /** Same bound {@link RaftCodec} uses; a {@code StoreRpc} frame is never larger in practice. */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  private StoreCodec() {}

  private static void checkFrameLength(int length) {
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
  }

  public static void write(OutputStream out, StoreRpc rpc) throws IOException {
    Frames.writeFrame(out, encodeBody(rpc));
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
      out.writeByte(CURRENT_VERSION);
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
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.GetDeploymentGeneration v -> {
          out.writeByte(TAG_GET_DEPLOYMENT_GENERATION);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListDeployments v -> out.writeByte(TAG_LIST_DEPLOYMENTS);
        case StoreRpc.GetService v -> {
          out.writeByte(TAG_GET_SERVICE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListServices v -> out.writeByte(TAG_LIST_SERVICES);
        case StoreRpc.GetNetworkPolicy v -> {
          out.writeByte(TAG_GET_NETWORK_POLICY);
          out.writeUTF(v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListNetworkPolicies v -> out.writeByte(TAG_LIST_NETWORK_POLICIES);
        case StoreRpc.GetIngress v -> {
          out.writeByte(TAG_GET_INGRESS);
          out.writeUTF(v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListIngresses v -> out.writeByte(TAG_LIST_INGRESSES);
        case StoreRpc.GetAlertRule v -> {
          out.writeByte(TAG_GET_ALERT_RULE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListAlertRules v -> out.writeByte(TAG_LIST_ALERT_RULES);
        case StoreRpc.GetLimitRange v -> {
          out.writeByte(TAG_GET_LIMIT_RANGE);
          out.writeUTF(v.tenantId());
        }
        case StoreRpc.ListLimitRanges v -> out.writeByte(TAG_LIST_LIMIT_RANGES);
        case StoreRpc.ListAssignmentsFor v -> {
          out.writeByte(TAG_LIST_ASSIGNMENTS_FOR);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.IsQuotaViolating v -> {
          out.writeByte(TAG_IS_QUOTA_VIOLATING);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.IsLimitRangeViolating v -> {
          out.writeByte(TAG_IS_LIMIT_RANGE_VIOLATING);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetLimitRangeViolationReason v -> {
          out.writeByte(TAG_GET_LIMIT_RANGE_VIOLATION_REASON);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetNodeTaints v -> {
          out.writeByte(TAG_GET_NODE_TAINTS);
          out.writeUTF(v.nodeId());
        }
        case StoreRpc.IsNodeCordoned v -> {
          out.writeByte(TAG_IS_NODE_CORDONED);
          out.writeUTF(v.nodeId());
        }
        case StoreRpc.IsCertificateRevoked v -> {
          out.writeByte(TAG_IS_CERTIFICATE_REVOKED);
          out.writeUTF(v.serialNumber());
        }
        case StoreRpc.ListRevokedCertificateSerials v ->
            out.writeByte(TAG_LIST_REVOKED_CERTIFICATE_SERIALS);
        case StoreRpc.GetSessionRevokedBeforeEpochMilli v -> {
          out.writeByte(TAG_GET_SESSION_REVOKED_BEFORE_EPOCH_MILLI);
          out.writeUTF(v.username());
        }
        case StoreRpc.GetWorkloadToken v -> {
          out.writeByte(TAG_GET_WORKLOAD_TOKEN);
          out.writeUTF(v.key());
        }
        case StoreRpc.ListAssignments v -> out.writeByte(TAG_LIST_ASSIGNMENTS);
        case StoreRpc.GetJobSpec v -> {
          out.writeByte(TAG_GET_JOB_SPEC);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListJobSpecs v -> out.writeByte(TAG_LIST_JOB_SPECS);
        case StoreRpc.ListJobRunsFor v -> {
          out.writeByte(TAG_LIST_JOB_RUNS_FOR);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.jobName());
        }
        case StoreRpc.ListJobRuns v -> out.writeByte(TAG_LIST_JOB_RUNS);
        case StoreRpc.GetJobPhase v -> {
          out.writeByte(TAG_GET_JOB_PHASE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.jobName());
        }
        case StoreRpc.GetJobRunSummary v -> {
          out.writeByte(TAG_GET_JOB_RUN_SUMMARY);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.jobName());
        }
        case StoreRpc.GetCronJobSpec v -> {
          out.writeByte(TAG_GET_CRONJOB_SPEC);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListCronJobSpecs v -> out.writeByte(TAG_LIST_CRONJOB_SPECS);
        case StoreRpc.GetCronJobLastSchedule v -> {
          out.writeByte(TAG_GET_CRONJOB_LAST_SCHEDULE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.GetDaemonSetSpec v -> {
          out.writeByte(TAG_GET_DAEMONSET_SPEC);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListDaemonSetSpecs v -> out.writeByte(TAG_LIST_DAEMONSET_SPECS);
        case StoreRpc.ListDaemonSetAssignments v -> out.writeByte(TAG_LIST_DAEMONSET_ASSIGNMENTS);
        case StoreRpc.ListDaemonSetAssignmentsFor v -> {
          out.writeByte(TAG_LIST_DAEMONSET_ASSIGNMENTS_FOR);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.daemonSetName());
        }
        case StoreRpc.ListRollingDaemonSetNodes v -> {
          out.writeByte(TAG_LIST_ROLLING_DAEMONSET_NODES);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.daemonSetName());
        }
        case StoreRpc.GetStatefulSetSpec v -> {
          out.writeByte(TAG_GET_STATEFULSET_SPEC);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.ListStatefulSetSpecs v -> out.writeByte(TAG_LIST_STATEFULSET_SPECS);
        case StoreRpc.ListStatefulSetAssignments v ->
            out.writeByte(TAG_LIST_STATEFULSET_ASSIGNMENTS);
        case StoreRpc.ListStatefulSetAssignmentsFor v -> {
          out.writeByte(TAG_LIST_STATEFULSET_ASSIGNMENTS_FOR);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.statefulSetName());
        }
        case StoreRpc.GetRollingStatefulSetIndex v -> {
          out.writeByte(TAG_GET_ROLLING_STATEFULSET_INDEX);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.statefulSetName());
        }
        case StoreRpc.GetStatefulSetIndexNode v -> {
          out.writeByte(TAG_GET_STATEFULSET_INDEX_NODE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.statefulSetName());
          out.writeInt(v.instanceIndex());
        }
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
        case StoreRpc.GetDeploymentLastScale v -> {
          out.writeByte(TAG_GET_DEPLOYMENT_LAST_SCALE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetEffectiveReplicas v -> {
          out.writeByte(TAG_GET_EFFECTIVE_REPLICAS);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.ListRollingIndices v -> {
          out.writeByte(TAG_LIST_ROLLING_INDICES);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.ListSurgeIndices v -> {
          out.writeByte(TAG_LIST_SURGE_INDICES);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
        }
        case StoreRpc.GetNodeHeartbeat v -> {
          out.writeByte(TAG_GET_NODE_HEARTBEAT);
          out.writeUTF(v.nodeId());
        }
        case StoreRpc.GetSnapshot v -> out.writeByte(TAG_GET_SNAPSHOT);
        case StoreRpc.GetAuditTrailStatus v -> out.writeByte(TAG_GET_AUDIT_TRAIL_STATUS);
        case StoreRpc.ListConfigEntriesForLinearizable v -> {
          out.writeByte(TAG_LIST_CONFIG_ENTRIES_FOR_LINEARIZABLE);
          out.writeUTF(v.tenantId());
        }
        case StoreRpc.GetReconcilerInstanceState v -> {
          out.writeByte(TAG_GET_RECONCILER_INSTANCE_STATE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
          out.writeInt(v.instanceIndex());
        }
        case StoreRpc.ListReconcilerInstanceStates v ->
            out.writeByte(TAG_LIST_RECONCILER_INSTANCE_STATES);
        case StoreRpc.GetWorkloadHealthState v -> {
          out.writeByte(TAG_GET_WORKLOAD_HEALTH_STATE);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.workloadKind());
          out.writeUTF(v.workloadName());
          out.writeUTF(v.slot());
        }
        case StoreRpc.ListWorkloadHealthStates v -> out.writeByte(TAG_LIST_WORKLOAD_HEALTH_STATES);
        case StoreRpc.ListInstanceEvents v -> {
          out.writeByte(TAG_LIST_INSTANCE_EVENTS);
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.deploymentName());
          out.writeInt(v.instanceIndex());
        }
        case StoreRpc.ListAllInstanceEvents v -> {
          out.writeByte(TAG_LIST_ALL_INSTANCE_EVENTS);
          DomainCodec.writeOptionalString(out, v.tenantId());
          DomainCodec.writeOptionalLong(out, v.since());
        }
        case StoreRpc.ListAuditEvents v -> {
          out.writeByte(TAG_LIST_AUDIT_EVENTS);
          DomainCodec.writeOptionalString(out, v.principal());
          DomainCodec.writeOptionalString(out, v.resourceKind());
          DomainCodec.writeOptionalString(out, v.tenantId());
          DomainCodec.writeOptionalLong(out, v.since());
        }
        case StoreRpc.ListControllerRevisions v -> {
          out.writeByte(TAG_LIST_CONTROLLER_REVISIONS);
          out.writeUTF(v.workloadKind());
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.GetControllerRevision v -> {
          out.writeByte(TAG_GET_CONTROLLER_REVISION);
          out.writeUTF(v.workloadKind());
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
          out.writeInt(v.revision());
        }
        case StoreRpc.ListKindDefinitions v -> out.writeByte(TAG_LIST_KIND_DEFINITIONS);
        case StoreRpc.GetKindDefinition v -> {
          out.writeByte(TAG_GET_KIND_DEFINITION);
          out.writeUTF(v.kindName());
        }
        case StoreRpc.ListCustomResources v -> {
          out.writeByte(TAG_LIST_CUSTOM_RESOURCES);
          out.writeUTF(v.kindName());
        }
        case StoreRpc.ListCustomResourcesFor v -> {
          out.writeByte(TAG_LIST_CUSTOM_RESOURCES_FOR);
          out.writeUTF(v.kindName());
          DomainCodec.writeOptionalString(out, v.tenantId());
        }
        case StoreRpc.GetCustomResource v -> {
          out.writeByte(TAG_GET_CUSTOM_RESOURCE);
          out.writeUTF(v.kindName());
          DomainCodec.writeOptionalString(out, v.tenantId());
          out.writeUTF(v.name());
        }
        case StoreRpc.Status v -> out.writeByte(TAG_STATUS);
        case StoreRpc.AddServer v -> {
          out.writeByte(TAG_ADD_SERVER);
          out.writeUTF(v.peerId());
          out.writeUTF(v.host());
          out.writeInt(v.raftPort());
          out.writeInt(v.clientPort());
        }
        case StoreRpc.RemoveServer v -> {
          out.writeByte(TAG_REMOVE_SERVER);
          out.writeUTF(v.peerId());
        }
        case StoreRpc.Ok v -> out.writeByte(TAG_OK);
        case StoreRpc.NotLeader v -> {
          out.writeByte(TAG_NOT_LEADER);
          out.writeUTF(v.leaderClientAddress());
        }
        case StoreRpc.MutationRejected v -> {
          out.writeByte(TAG_MUTATION_REJECTED);
          out.writeUTF(v.reason());
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
        case StoreRpc.GenerationResult v -> {
          out.writeByte(TAG_GENERATION_RESULT);
          out.writeLong(v.value());
        }
        case StoreRpc.ServiceResult v -> {
          out.writeByte(TAG_SERVICE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeServiceSpec(out, v.value());
          }
        }
        case StoreRpc.ServiceListResult v -> {
          out.writeByte(TAG_SERVICE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (ServiceSpec s : v.values()) {
            DomainCodec.writeServiceSpec(out, s);
          }
        }
        case StoreRpc.NetworkPolicyResult v -> {
          out.writeByte(TAG_NETWORK_POLICY_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeNetworkPolicySpec(out, v.value());
          }
        }
        case StoreRpc.NetworkPolicyListResult v -> {
          out.writeByte(TAG_NETWORK_POLICY_LIST_RESULT);
          out.writeInt(v.values().size());
          for (NetworkPolicySpec s : v.values()) {
            DomainCodec.writeNetworkPolicySpec(out, s);
          }
        }
        case StoreRpc.IngressResult v -> {
          out.writeByte(TAG_INGRESS_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeIngressSpec(out, v.value());
          }
        }
        case StoreRpc.IngressListResult v -> {
          out.writeByte(TAG_INGRESS_LIST_RESULT);
          out.writeInt(v.values().size());
          for (IngressSpec s : v.values()) {
            DomainCodec.writeIngressSpec(out, s);
          }
        }
        case StoreRpc.AlertRuleResult v -> {
          out.writeByte(TAG_ALERT_RULE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeAlertRuleSpec(out, v.value());
          }
        }
        case StoreRpc.AlertRuleListResult v -> {
          out.writeByte(TAG_ALERT_RULE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (AlertRuleSpec s : v.values()) {
            DomainCodec.writeAlertRuleSpec(out, s);
          }
        }
        case StoreRpc.LimitRangeResult v -> {
          out.writeByte(TAG_LIMIT_RANGE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeLimitRangeSpec(out, v.value());
          }
        }
        case StoreRpc.LimitRangeListResult v -> {
          out.writeByte(TAG_LIMIT_RANGE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (LimitRangeSpec s : v.values()) {
            DomainCodec.writeLimitRangeSpec(out, s);
          }
        }
        case StoreRpc.JobSpecResult v -> {
          out.writeByte(TAG_JOB_SPEC_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeJobSpec(out, v.value());
          }
        }
        case StoreRpc.JobSpecListResult v -> {
          out.writeByte(TAG_JOB_SPEC_LIST_RESULT);
          out.writeInt(v.values().size());
          for (JobSpec s : v.values()) {
            DomainCodec.writeJobSpec(out, s);
          }
        }
        case StoreRpc.JobRunListResult v -> {
          out.writeByte(TAG_JOB_RUN_LIST_RESULT);
          out.writeInt(v.values().size());
          for (JobRun r : v.values()) {
            DomainCodec.writeJobRun(out, r);
          }
        }
        case StoreRpc.JobPhaseResult v -> {
          out.writeByte(TAG_JOB_PHASE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            out.writeUTF(v.value().name());
          }
        }
        case StoreRpc.JobRunSummaryResult v -> {
          out.writeByte(TAG_JOB_RUN_SUMMARY_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeJobRunSummary(out, v.value());
          }
        }
        case StoreRpc.CronJobSpecResult v -> {
          out.writeByte(TAG_CRONJOB_SPEC_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeCronJobSpec(out, v.value());
          }
        }
        case StoreRpc.CronJobSpecListResult v -> {
          out.writeByte(TAG_CRONJOB_SPEC_LIST_RESULT);
          out.writeInt(v.values().size());
          for (CronJobSpec s : v.values()) {
            DomainCodec.writeCronJobSpec(out, s);
          }
        }
        case StoreRpc.InstantResult v -> {
          out.writeByte(TAG_INSTANT_RESULT);
          out.writeBoolean(v.present());
          out.writeLong(v.epochMilli());
        }
        case StoreRpc.DaemonSetSpecResult v -> {
          out.writeByte(TAG_DAEMONSET_SPEC_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeDaemonSetSpec(out, v.value());
          }
        }
        case StoreRpc.DaemonSetSpecListResult v -> {
          out.writeByte(TAG_DAEMONSET_SPEC_LIST_RESULT);
          out.writeInt(v.values().size());
          for (DaemonSetSpec s : v.values()) {
            DomainCodec.writeDaemonSetSpec(out, s);
          }
        }
        case StoreRpc.DaemonSetAssignmentListResult v -> {
          out.writeByte(TAG_DAEMONSET_ASSIGNMENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (DaemonSetAssignment a : v.values()) {
            DomainCodec.writeDaemonSetAssignment(out, a);
          }
        }
        case StoreRpc.StatefulSetSpecResult v -> {
          out.writeByte(TAG_STATEFULSET_SPEC_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeStatefulSetSpec(out, v.value());
          }
        }
        case StoreRpc.StatefulSetSpecListResult v -> {
          out.writeByte(TAG_STATEFULSET_SPEC_LIST_RESULT);
          out.writeInt(v.values().size());
          for (StatefulSetSpec s : v.values()) {
            DomainCodec.writeStatefulSetSpec(out, s);
          }
        }
        case StoreRpc.IntSetResult v -> {
          out.writeByte(TAG_INT_SET_RESULT);
          out.writeInt(v.values().size());
          for (int value : v.values()) {
            out.writeInt(value);
          }
        }
        case StoreRpc.IntIntMapResult v -> {
          out.writeByte(TAG_INT_INT_MAP_RESULT);
          out.writeInt(v.surgeIndices().size());
          for (int i = 0; i < v.surgeIndices().size(); i++) {
            out.writeInt(v.surgeIndices().get(i));
            out.writeInt(v.targetIndices().get(i));
          }
        }
        case StoreRpc.StringSetResult v -> {
          out.writeByte(TAG_STRING_SET_RESULT);
          out.writeInt(v.values().size());
          for (String value : v.values()) {
            out.writeUTF(value);
          }
        }
        case StoreRpc.KindDefinitionResult v -> {
          out.writeByte(TAG_KIND_DEFINITION_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeKindDefinitionSpec(out, v.value());
          }
        }
        case StoreRpc.KindDefinitionListResult v -> {
          out.writeByte(TAG_KIND_DEFINITION_LIST_RESULT);
          out.writeInt(v.values().size());
          for (KindDefinitionSpec definition : v.values()) {
            DomainCodec.writeKindDefinitionSpec(out, definition);
          }
        }
        case StoreRpc.CustomResourceResult v -> {
          out.writeByte(TAG_CUSTOM_RESOURCE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeCustomResource(out, v.value());
          }
        }
        case StoreRpc.CustomResourceListResult v -> {
          out.writeByte(TAG_CUSTOM_RESOURCE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (CustomResource resource : v.values()) {
            DomainCodec.writeCustomResource(out, resource);
          }
        }
        case StoreRpc.StatusResult v -> {
          out.writeByte(TAG_STATUS_RESULT);
          out.writeUTF(v.selfId());
          out.writeBoolean(v.leader());
          out.writeUTF(v.leaderId());
          out.writeInt(v.memberIds().size());
          for (String memberId : v.memberIds()) {
            out.writeUTF(memberId);
          }
        }
        case StoreRpc.StatefulSetAssignmentListResult v -> {
          out.writeByte(TAG_STATEFULSET_ASSIGNMENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (StatefulSetAssignment a : v.values()) {
            DomainCodec.writeStatefulSetAssignment(out, a);
          }
        }
        case StoreRpc.StringResult v -> {
          out.writeByte(TAG_STRING_RESULT);
          out.writeBoolean(v.present());
          out.writeUTF(v.value());
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
        case StoreRpc.WorkloadTokenResult v -> {
          out.writeByte(TAG_WORKLOAD_TOKEN_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeWorkloadTokenRecord(out, v.value());
          }
        }
        case StoreRpc.SnapshotResult v -> {
          out.writeByte(TAG_SNAPSHOT_RESULT);
          DomainCodec.writeBytes(out, v.snapshot());
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
          for (Role r : v.values()) {
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
        case StoreRpc.WorkloadHealthStateResult v -> {
          out.writeByte(TAG_WORKLOAD_HEALTH_STATE_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeWorkloadHealthState(out, v.value());
          }
        }
        case StoreRpc.WorkloadHealthStateListResult v -> {
          out.writeByte(TAG_WORKLOAD_HEALTH_STATE_LIST_RESULT);
          out.writeInt(v.values().size());
          for (WorkloadHealthState s : v.values()) {
            DomainCodec.writeWorkloadHealthState(out, s);
          }
        }
        case StoreRpc.InstanceEventListResult v -> {
          out.writeByte(TAG_INSTANCE_EVENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (InstanceEvent e : v.values()) {
            DomainCodec.writeInstanceEvent(out, e);
          }
        }
        case StoreRpc.AuditEventListResult v -> {
          out.writeByte(TAG_AUDIT_EVENT_LIST_RESULT);
          out.writeInt(v.values().size());
          for (AuditEvent e : v.values()) {
            DomainCodec.writeAuditEvent(out, e);
          }
        }
        case StoreRpc.AuditTrailStatusResult v -> {
          out.writeByte(TAG_AUDIT_TRAIL_STATUS_RESULT);
          DomainCodec.writeAuditTrailStatus(out, v.status());
        }
        case StoreRpc.ControllerRevisionListResult v -> {
          out.writeByte(TAG_CONTROLLER_REVISION_LIST_RESULT);
          out.writeInt(v.values().size());
          for (ControllerRevision r : v.values()) {
            DomainCodec.writeControllerRevision(out, r);
          }
        }
        case StoreRpc.ControllerRevisionResult v -> {
          out.writeByte(TAG_CONTROLLER_REVISION_RESULT);
          out.writeBoolean(v.present());
          if (v.present()) {
            DomainCodec.writeControllerRevision(out, v.value());
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
      int version = in.readByte();
      GimleCodecException.checkVersion(version, CURRENT_VERSION);
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
        case TAG_GET_DEPLOYMENT ->
            new StoreRpc.GetDeployment(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_DEPLOYMENT_GENERATION ->
            new StoreRpc.GetDeploymentGeneration(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_DEPLOYMENTS -> new StoreRpc.ListDeployments();
        case TAG_GET_SERVICE ->
            new StoreRpc.GetService(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_SERVICES -> new StoreRpc.ListServices();
        case TAG_GET_NETWORK_POLICY -> new StoreRpc.GetNetworkPolicy(in.readUTF(), in.readUTF());
        case TAG_LIST_NETWORK_POLICIES -> new StoreRpc.ListNetworkPolicies();
        case TAG_GET_INGRESS -> new StoreRpc.GetIngress(in.readUTF(), in.readUTF());
        case TAG_LIST_INGRESSES -> new StoreRpc.ListIngresses();
        case TAG_GET_ALERT_RULE -> {
          Optional<String> tenantId = DomainCodec.readOptionalString(in);
          yield new StoreRpc.GetAlertRule(tenantId, in.readUTF());
        }
        case TAG_LIST_ALERT_RULES -> new StoreRpc.ListAlertRules();
        case TAG_GET_LIMIT_RANGE -> new StoreRpc.GetLimitRange(in.readUTF());
        case TAG_LIST_LIMIT_RANGES -> new StoreRpc.ListLimitRanges();
        case TAG_LIST_ASSIGNMENTS_FOR ->
            new StoreRpc.ListAssignmentsFor(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_IS_QUOTA_VIOLATING ->
            new StoreRpc.IsQuotaViolating(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_IS_LIMIT_RANGE_VIOLATING ->
            new StoreRpc.IsLimitRangeViolating(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_LIMIT_RANGE_VIOLATION_REASON ->
            new StoreRpc.GetLimitRangeViolationReason(
                DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_IS_NODE_CORDONED -> new StoreRpc.IsNodeCordoned(in.readUTF());
        case TAG_GET_NODE_TAINTS -> new StoreRpc.GetNodeTaints(in.readUTF());
        case TAG_IS_CERTIFICATE_REVOKED -> new StoreRpc.IsCertificateRevoked(in.readUTF());
        case TAG_LIST_REVOKED_CERTIFICATE_SERIALS -> new StoreRpc.ListRevokedCertificateSerials();
        case TAG_GET_SESSION_REVOKED_BEFORE_EPOCH_MILLI ->
            new StoreRpc.GetSessionRevokedBeforeEpochMilli(in.readUTF());
        case TAG_GET_WORKLOAD_TOKEN -> new StoreRpc.GetWorkloadToken(in.readUTF());
        case TAG_LIST_ASSIGNMENTS -> new StoreRpc.ListAssignments();
        case TAG_GET_JOB_SPEC ->
            new StoreRpc.GetJobSpec(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_JOB_SPECS -> new StoreRpc.ListJobSpecs();
        case TAG_LIST_JOB_RUNS_FOR ->
            new StoreRpc.ListJobRunsFor(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_JOB_RUNS -> new StoreRpc.ListJobRuns();
        case TAG_GET_JOB_PHASE ->
            new StoreRpc.GetJobPhase(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_JOB_RUN_SUMMARY ->
            new StoreRpc.GetJobRunSummary(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_CRONJOB_SPEC ->
            new StoreRpc.GetCronJobSpec(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_CRONJOB_SPECS -> new StoreRpc.ListCronJobSpecs();
        case TAG_GET_CRONJOB_LAST_SCHEDULE ->
            new StoreRpc.GetCronJobLastSchedule(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_DAEMONSET_SPEC ->
            new StoreRpc.GetDaemonSetSpec(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_DAEMONSET_SPECS -> new StoreRpc.ListDaemonSetSpecs();
        case TAG_LIST_DAEMONSET_ASSIGNMENTS -> new StoreRpc.ListDaemonSetAssignments();
        case TAG_LIST_DAEMONSET_ASSIGNMENTS_FOR ->
            new StoreRpc.ListDaemonSetAssignmentsFor(
                DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_ROLLING_DAEMONSET_NODES ->
            new StoreRpc.ListRollingDaemonSetNodes(
                DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_STATEFULSET_SPEC ->
            new StoreRpc.GetStatefulSetSpec(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_STATEFULSET_SPECS -> new StoreRpc.ListStatefulSetSpecs();
        case TAG_LIST_STATEFULSET_ASSIGNMENTS -> new StoreRpc.ListStatefulSetAssignments();
        case TAG_LIST_STATEFULSET_ASSIGNMENTS_FOR ->
            new StoreRpc.ListStatefulSetAssignmentsFor(
                DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_ROLLING_STATEFULSET_INDEX ->
            new StoreRpc.GetRollingStatefulSetIndex(
                DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_STATEFULSET_INDEX_NODE ->
            new StoreRpc.GetStatefulSetIndexNode(
                DomainCodec.readOptionalString(in), in.readUTF(), in.readInt());
        case TAG_LIST_NODE_REGISTRATIONS -> new StoreRpc.ListNodeRegistrations();
        case TAG_LIST_TENANTS -> new StoreRpc.ListTenants();
        case TAG_LIST_CONFIG_ENTRIES_FOR -> new StoreRpc.ListConfigEntriesFor(in.readUTF());
        case TAG_LIST_ROLES -> new StoreRpc.ListRoles();
        case TAG_GET_ROLE -> new StoreRpc.GetRole(in.readUTF());
        case TAG_LIST_ROLE_BINDINGS -> new StoreRpc.ListRoleBindings();
        case TAG_GET_ROLE_BINDING -> new StoreRpc.GetRoleBinding(in.readUTF());
        case TAG_GET_ACCOUNT -> new StoreRpc.GetAccount(in.readUTF());
        case TAG_GET_NODE_REGISTRATION -> new StoreRpc.GetNodeRegistration(in.readUTF());
        case TAG_GET_EFFECTIVE_REPLICAS ->
            new StoreRpc.GetEffectiveReplicas(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_DEPLOYMENT_LAST_SCALE ->
            new StoreRpc.GetDeploymentLastScale(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_ROLLING_INDICES ->
            new StoreRpc.ListRollingIndices(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_LIST_SURGE_INDICES ->
            new StoreRpc.ListSurgeIndices(DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_NODE_HEARTBEAT -> new StoreRpc.GetNodeHeartbeat(in.readUTF());
        case TAG_GET_SNAPSHOT -> new StoreRpc.GetSnapshot();
        case TAG_GET_AUDIT_TRAIL_STATUS -> new StoreRpc.GetAuditTrailStatus();
        case TAG_LIST_CONFIG_ENTRIES_FOR_LINEARIZABLE ->
            new StoreRpc.ListConfigEntriesForLinearizable(in.readUTF());
        case TAG_GET_RECONCILER_INSTANCE_STATE ->
            new StoreRpc.GetReconcilerInstanceState(
                DomainCodec.readOptionalString(in), in.readUTF(), in.readInt());
        case TAG_LIST_RECONCILER_INSTANCE_STATES -> new StoreRpc.ListReconcilerInstanceStates();
        case TAG_GET_WORKLOAD_HEALTH_STATE ->
            new StoreRpc.GetWorkloadHealthState(
                DomainCodec.readOptionalString(in), in.readUTF(), in.readUTF(), in.readUTF());
        case TAG_LIST_WORKLOAD_HEALTH_STATES -> new StoreRpc.ListWorkloadHealthStates();
        case TAG_ADD_SERVER ->
            new StoreRpc.AddServer(in.readUTF(), in.readUTF(), in.readInt(), in.readInt());
        case TAG_REMOVE_SERVER -> new StoreRpc.RemoveServer(in.readUTF());
        case TAG_LIST_INSTANCE_EVENTS ->
            new StoreRpc.ListInstanceEvents(
                DomainCodec.readOptionalString(in), in.readUTF(), in.readInt());
        case TAG_LIST_ALL_INSTANCE_EVENTS ->
            new StoreRpc.ListAllInstanceEvents(
                DomainCodec.readOptionalString(in), DomainCodec.readOptionalLong(in));
        case TAG_LIST_AUDIT_EVENTS ->
            new StoreRpc.ListAuditEvents(
                DomainCodec.readOptionalString(in),
                DomainCodec.readOptionalString(in),
                DomainCodec.readOptionalString(in),
                DomainCodec.readOptionalLong(in));
        case TAG_LIST_CONTROLLER_REVISIONS ->
            new StoreRpc.ListControllerRevisions(
                in.readUTF(), DomainCodec.readOptionalString(in), in.readUTF());
        case TAG_GET_CONTROLLER_REVISION ->
            new StoreRpc.GetControllerRevision(
                in.readUTF(), DomainCodec.readOptionalString(in), in.readUTF(), in.readInt());
        case TAG_LIST_KIND_DEFINITIONS -> new StoreRpc.ListKindDefinitions();
        case TAG_GET_KIND_DEFINITION -> new StoreRpc.GetKindDefinition(in.readUTF());
        case TAG_LIST_CUSTOM_RESOURCES -> new StoreRpc.ListCustomResources(in.readUTF());
        case TAG_LIST_CUSTOM_RESOURCES_FOR ->
            new StoreRpc.ListCustomResourcesFor(in.readUTF(), DomainCodec.readOptionalString(in));
        case TAG_GET_CUSTOM_RESOURCE -> {
          String kindName = in.readUTF();
          yield new StoreRpc.GetCustomResource(
              kindName, DomainCodec.readOptionalString(in), in.readUTF());
        }
        case TAG_STATUS -> new StoreRpc.Status();
        case TAG_OK -> new StoreRpc.Ok();
        case TAG_NOT_LEADER -> new StoreRpc.NotLeader(in.readUTF());
        case TAG_MUTATION_REJECTED -> new StoreRpc.MutationRejected(in.readUTF());
        case TAG_LEASE_RESULT ->
            new StoreRpc.LeaseResult(in.readBoolean(), in.readUTF(), in.readLong());
        case TAG_BOOL_RESULT -> new StoreRpc.BoolResult(in.readBoolean());
        case TAG_INT_RESULT -> new StoreRpc.IntResult(in.readBoolean(), in.readInt());
        case TAG_DEPLOYMENT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.DeploymentResult(
              present, present ? DomainCodec.readDeploymentSpec(in) : null);
        }
        case TAG_GENERATION_RESULT -> new StoreRpc.GenerationResult(in.readLong());
        case TAG_SERVICE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.ServiceResult(
              present, present ? DomainCodec.readServiceSpec(in) : null);
        }
        case TAG_SERVICE_LIST_RESULT -> {
          int count = in.readInt();
          List<ServiceSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readServiceSpec(in));
          }
          yield new StoreRpc.ServiceListResult(values);
        }
        case TAG_NETWORK_POLICY_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.NetworkPolicyResult(
              present, present ? DomainCodec.readNetworkPolicySpec(in) : null);
        }
        case TAG_INGRESS_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.IngressResult(
              present, present ? DomainCodec.readIngressSpec(in) : null);
        }
        case TAG_INGRESS_LIST_RESULT -> {
          int count = in.readInt();
          List<IngressSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readIngressSpec(in));
          }
          yield new StoreRpc.IngressListResult(values);
        }
        case TAG_NETWORK_POLICY_LIST_RESULT -> {
          int count = in.readInt();
          List<NetworkPolicySpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readNetworkPolicySpec(in));
          }
          yield new StoreRpc.NetworkPolicyListResult(values);
        }
        case TAG_ALERT_RULE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.AlertRuleResult(
              present, present ? DomainCodec.readAlertRuleSpec(in) : null);
        }
        case TAG_ALERT_RULE_LIST_RESULT -> {
          int count = in.readInt();
          List<AlertRuleSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readAlertRuleSpec(in));
          }
          yield new StoreRpc.AlertRuleListResult(values);
        }
        case TAG_LIMIT_RANGE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.LimitRangeResult(
              present, present ? DomainCodec.readLimitRangeSpec(in) : null);
        }
        case TAG_LIMIT_RANGE_LIST_RESULT -> {
          int count = in.readInt();
          List<LimitRangeSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readLimitRangeSpec(in));
          }
          yield new StoreRpc.LimitRangeListResult(values);
        }
        case TAG_JOB_SPEC_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.JobSpecResult(present, present ? DomainCodec.readJobSpec(in) : null);
        }
        case TAG_JOB_SPEC_LIST_RESULT -> {
          int count = in.readInt();
          List<JobSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readJobSpec(in));
          }
          yield new StoreRpc.JobSpecListResult(values);
        }
        case TAG_JOB_RUN_LIST_RESULT -> {
          int count = in.readInt();
          List<JobRun> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readJobRun(in));
          }
          yield new StoreRpc.JobRunListResult(values);
        }
        case TAG_JOB_PHASE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.JobPhaseResult(
              present, present ? JobPhase.valueOf(in.readUTF()) : null);
        }
        case TAG_JOB_RUN_SUMMARY_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.JobRunSummaryResult(
              present, present ? DomainCodec.readJobRunSummary(in) : null);
        }
        case TAG_CRONJOB_SPEC_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.CronJobSpecResult(
              present, present ? DomainCodec.readCronJobSpec(in) : null);
        }
        case TAG_CRONJOB_SPEC_LIST_RESULT -> {
          int count = in.readInt();
          List<CronJobSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readCronJobSpec(in));
          }
          yield new StoreRpc.CronJobSpecListResult(values);
        }
        case TAG_INSTANT_RESULT -> new StoreRpc.InstantResult(in.readBoolean(), in.readLong());
        case TAG_DAEMONSET_SPEC_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.DaemonSetSpecResult(
              present, present ? DomainCodec.readDaemonSetSpec(in) : null);
        }
        case TAG_DAEMONSET_SPEC_LIST_RESULT -> {
          int count = in.readInt();
          List<DaemonSetSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readDaemonSetSpec(in));
          }
          yield new StoreRpc.DaemonSetSpecListResult(values);
        }
        case TAG_DAEMONSET_ASSIGNMENT_LIST_RESULT -> {
          int count = in.readInt();
          List<DaemonSetAssignment> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readDaemonSetAssignment(in));
          }
          yield new StoreRpc.DaemonSetAssignmentListResult(values);
        }
        case TAG_STATEFULSET_SPEC_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.StatefulSetSpecResult(
              present, present ? DomainCodec.readStatefulSetSpec(in) : null);
        }
        case TAG_STATEFULSET_SPEC_LIST_RESULT -> {
          int count = in.readInt();
          List<StatefulSetSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readStatefulSetSpec(in));
          }
          yield new StoreRpc.StatefulSetSpecListResult(values);
        }
        case TAG_STATEFULSET_ASSIGNMENT_LIST_RESULT -> {
          int count = in.readInt();
          List<StatefulSetAssignment> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readStatefulSetAssignment(in));
          }
          yield new StoreRpc.StatefulSetAssignmentListResult(values);
        }
        case TAG_STRING_RESULT -> new StoreRpc.StringResult(in.readBoolean(), in.readUTF());
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
        case TAG_WORKLOAD_TOKEN_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.WorkloadTokenResult(
              present, present ? DomainCodec.readWorkloadTokenRecord(in) : null);
        }
        case TAG_HEARTBEAT_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.HeartbeatResult(
              present, present ? DomainCodec.readObservedHeartbeat(in) : null);
        }
        case TAG_SNAPSHOT_RESULT -> new StoreRpc.SnapshotResult(DomainCodec.readBytes(in));
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
        case TAG_INT_SET_RESULT -> {
          int count = in.readInt();
          List<Integer> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(in.readInt());
          }
          yield new StoreRpc.IntSetResult(values);
        }
        case TAG_INT_INT_MAP_RESULT -> {
          int count = in.readInt();
          List<Integer> surgeIndices = new ArrayList<>();
          List<Integer> targetIndices = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            surgeIndices.add(in.readInt());
            targetIndices.add(in.readInt());
          }
          yield new StoreRpc.IntIntMapResult(surgeIndices, targetIndices);
        }
        case TAG_STRING_SET_RESULT -> {
          int count = in.readInt();
          List<String> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(in.readUTF());
          }
          yield new StoreRpc.StringSetResult(values);
        }
        case TAG_STATUS_RESULT -> {
          String selfId = in.readUTF();
          boolean leader = in.readBoolean();
          String leaderId = in.readUTF();
          int memberCount = in.readInt();
          List<String> memberIds = new ArrayList<>();
          for (int i = 0; i < memberCount; i++) {
            memberIds.add(in.readUTF());
          }
          yield new StoreRpc.StatusResult(selfId, leader, leaderId, memberIds);
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
          List<Role> values = new ArrayList<>();
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
        case TAG_WORKLOAD_HEALTH_STATE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.WorkloadHealthStateResult(
              present, present ? DomainCodec.readWorkloadHealthState(in) : null);
        }
        case TAG_WORKLOAD_HEALTH_STATE_LIST_RESULT -> {
          int count = in.readInt();
          List<WorkloadHealthState> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readWorkloadHealthState(in));
          }
          yield new StoreRpc.WorkloadHealthStateListResult(values);
        }
        case TAG_INSTANCE_EVENT_LIST_RESULT -> {
          int count = in.readInt();
          List<InstanceEvent> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readInstanceEvent(in));
          }
          yield new StoreRpc.InstanceEventListResult(values);
        }
        case TAG_AUDIT_EVENT_LIST_RESULT -> {
          int count = in.readInt();
          List<AuditEvent> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readAuditEvent(in));
          }
          yield new StoreRpc.AuditEventListResult(values);
        }
        case TAG_AUDIT_TRAIL_STATUS_RESULT ->
            new StoreRpc.AuditTrailStatusResult(DomainCodec.readAuditTrailStatus(in));
        case TAG_CONTROLLER_REVISION_LIST_RESULT -> {
          int count = in.readInt();
          List<ControllerRevision> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readControllerRevision(in));
          }
          yield new StoreRpc.ControllerRevisionListResult(values);
        }
        case TAG_CONTROLLER_REVISION_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.ControllerRevisionResult(
              present, present ? DomainCodec.readControllerRevision(in) : null);
        }
        case TAG_KIND_DEFINITION_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.KindDefinitionResult(
              present, present ? DomainCodec.readKindDefinitionSpec(in) : null);
        }
        case TAG_KIND_DEFINITION_LIST_RESULT -> {
          int count = in.readInt();
          List<KindDefinitionSpec> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readKindDefinitionSpec(in));
          }
          yield new StoreRpc.KindDefinitionListResult(values);
        }
        case TAG_CUSTOM_RESOURCE_RESULT -> {
          boolean present = in.readBoolean();
          yield new StoreRpc.CustomResourceResult(
              present, present ? DomainCodec.readCustomResource(in) : null);
        }
        case TAG_CUSTOM_RESOURCE_LIST_RESULT -> {
          int count = in.readInt();
          List<CustomResource> values = new ArrayList<>();
          for (int i = 0; i < count; i++) {
            values.add(DomainCodec.readCustomResource(in));
          }
          yield new StoreRpc.CustomResourceListResult(values);
        }
        default -> throw new IllegalArgumentException("unknown StoreRpc tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
