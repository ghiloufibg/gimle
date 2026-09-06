package com.gimle.mimir.codec;

import com.gimle.core.authz.Account;
import com.gimle.core.authz.Permission;
import com.gimle.core.authz.ResourceKind;
import com.gimle.core.authz.RoleBinding;
import com.gimle.core.authz.Verb;
import com.gimle.core.config.ConfigEntry;
import com.gimle.core.exception.GimleCodecException;
import com.gimle.core.ingress.IngressRule;
import com.gimle.core.module.IsolationTier;
import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.core.protocol.AuditTrailStatus;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.tenant.TenantIsolationPosture;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.galdr.CustomResource;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import com.gimle.mimir.galdr.KindNames;
import com.gimle.mimir.galdr.KindScope;
import com.gimle.mimir.galdr.PrintColumn;
import com.gimle.mimir.galdr.SchemaField;
import com.gimle.mimir.galdr.SchemaModel;
import com.gimle.mimir.manifest.AlertRuleSpec;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.ConcurrencyPolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.IngressSpec;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.JobTemplate;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.PlacementConstraints;
import com.gimle.mimir.manifest.ServiceProtocol;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.manifest.StatefulSetSpec;
import com.gimle.mimir.manifest.WorkloadSpec;
import com.gimle.mimir.store.ControllerRevision;
import com.gimle.mimir.store.DaemonSetAssignment;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.JobRun;
import com.gimle.mimir.store.JobRunSummary;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.ReconcilerInstanceState;
import com.gimle.mimir.store.RequestOutcomeRecord;
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadHealthState;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Binary (de)serialization for the domain types shared by {@link com.gimle.mimir.raft.RaftCodec}
 * (the Raft log/RPC wire format) and {@link com.gimle.mimir.rpc.StoreCodec} (the client-facing
 * {@code StoreRpc} wire format) -- pulled out once both codecs needed the exact same {@code
 * DeploymentSpec}/{@code InstanceAssignment}/{@code NodeRegistration}/{@code Tenant}/{@code
 * ConfigEntry}/RBAC encoding, rather than the second codec copying ~150 lines from the first.
 * Deliberately distinct from the *transport*-plumbing sharing (accept-loop, socket lifecycle, TLS)
 * that stays duplicated between {@code RaftTransport} and {@code StoreTransport} for now -- this is
 * data encoding, not networking, and the DRY case for it was immediate rather than deferred.
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
    writeOptionalString(out, spec.artifactSha256());
    writeOptionalDisruptionBudget(out, spec.disruption());
    writeOptionalVesselSpec(out, spec.vessel());
    out.writeInt(spec.configMapRefs().size());
    for (String name : spec.configMapRefs()) {
      out.writeUTF(name);
    }
    out.writeInt(spec.secretMapRefs().size());
    for (String name : spec.secretMapRefs()) {
      out.writeUTF(name);
    }
  }

  public static DeploymentSpec readDeploymentSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    int replicas = in.readInt();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<AutoscalePolicy> autoscale = readOptionalAutoscalePolicy(in);
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> artifactSha256 = readOptionalString(in);
    Optional<DisruptionBudget> disruption = readOptionalDisruptionBudget(in);
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    int configMapRefCount = in.readInt();
    List<String> configMapRefs = new ArrayList<>();
    for (int i = 0; i < configMapRefCount; i++) {
      configMapRefs.add(in.readUTF());
    }
    int secretMapRefCount = in.readInt();
    List<String> secretMapRefs = new ArrayList<>();
    for (int i = 0; i < secretMapRefCount; i++) {
      secretMapRefs.add(in.readUTF());
    }
    return new DeploymentSpec(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        autoscale,
        tenantId,
        artifactSha256,
        disruption,
        vessel,
        configMapRefs,
        secretMapRefs);
  }

  public static void writeServiceSpec(DataOutputStream out, ServiceSpec spec) throws IOException {
    out.writeUTF(spec.name());
    writeOptionalString(out, spec.tenantId());
    out.writeInt(spec.deploymentNames().size());
    for (String deploymentName : spec.deploymentNames()) {
      out.writeUTF(deploymentName);
    }
    out.writeInt(spec.port());
    // 0 encodes "no targetPort declared" -- a real port is always in [1, 65535], so the sentinel
    // is unambiguous and keeps this field a plain int on the wire.
    out.writeInt(spec.targetPort().orElse(0));
    out.writeBoolean(spec.sessionAffinity());
    writeOptionalString(out, spec.externalName());
    out.writeUTF(spec.protocol().name());
  }

  public static ServiceSpec readServiceSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    int deploymentNameCount = in.readInt();
    Set<String> deploymentNames = new LinkedHashSet<>();
    for (int i = 0; i < deploymentNameCount; i++) {
      deploymentNames.add(in.readUTF());
    }
    int port = in.readInt();
    int rawTargetPort = in.readInt();
    OptionalInt targetPort =
        rawTargetPort == 0 ? OptionalInt.empty() : OptionalInt.of(rawTargetPort);
    boolean sessionAffinity = in.readBoolean();
    Optional<String> externalName = readOptionalString(in);
    ServiceProtocol protocol = ServiceProtocol.valueOf(in.readUTF());
    return new ServiceSpec(
        name, tenantId, deploymentNames, port, targetPort, sessionAffinity, externalName, protocol);
  }

  public static void writeNetworkPolicySpec(DataOutputStream out, NetworkPolicySpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    out.writeUTF(spec.tenantId());
    writeOptionalStringSet(out, spec.deploymentNames());
    writeOptionalStringSet(out, spec.serviceInterfaceNames());
    writeOptionalStringSet(out, spec.allowedCallerTenantIds());
    writeOptionalStringSet(out, spec.allowedCalleeTenantIds());
    out.writeInt(spec.version());
  }

  public static NetworkPolicySpec readNetworkPolicySpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String tenantId = in.readUTF();
    Optional<Set<String>> deploymentNames = readOptionalStringSet(in);
    Optional<Set<String>> serviceInterfaceNames = readOptionalStringSet(in);
    Optional<Set<String>> allowedCallerTenantIds = readOptionalStringSet(in);
    Optional<Set<String>> allowedCalleeTenantIds = readOptionalStringSet(in);
    int version = in.readInt();
    return new NetworkPolicySpec(
        name,
        tenantId,
        deploymentNames,
        serviceInterfaceNames,
        allowedCallerTenantIds,
        allowedCalleeTenantIds,
        version);
  }

  public static void writeIngressSpec(DataOutputStream out, IngressSpec spec) throws IOException {
    out.writeUTF(spec.name());
    out.writeUTF(spec.tenantId());
    out.writeInt(spec.routes().size());
    for (IngressRule route : spec.routes()) {
      writeIngressRule(out, route);
    }
    out.writeInt(spec.version());
  }

  public static IngressSpec readIngressSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String tenantId = in.readUTF();
    int routeCount = in.readInt();
    List<IngressRule> routes = new ArrayList<>(routeCount);
    for (int i = 0; i < routeCount; i++) {
      routes.add(readIngressRule(in));
    }
    int version = in.readInt();
    return new IngressSpec(name, tenantId, routes, version);
  }

  private static void writeIngressRule(DataOutputStream out, IngressRule route) throws IOException {
    writeOptionalString(out, route.host());
    out.writeUTF(route.path());
    out.writeBoolean(route.prefix());
    out.writeUTF(route.kind().name());
    writeOptionalString(out, route.serviceName());
    writeOptionalString(out, route.deploymentName());
    writeOptionalString(out, route.portName());
    writeOptionalString(out, route.interfaceName());
    out.writeInt(route.majorVersion());
    writeOptionalString(out, route.methodName());
    writeOptionalString(out, route.paramType());
  }

  private static IngressRule readIngressRule(DataInputStream in) throws IOException {
    Optional<String> host = readOptionalString(in);
    String path = in.readUTF();
    boolean prefix = in.readBoolean();
    IngressRule.Kind kind = IngressRule.Kind.valueOf(in.readUTF());
    Optional<String> serviceName = readOptionalString(in);
    Optional<String> deploymentName = readOptionalString(in);
    Optional<String> portName = readOptionalString(in);
    Optional<String> interfaceName = readOptionalString(in);
    int majorVersion = in.readInt();
    Optional<String> methodName = readOptionalString(in);
    Optional<String> paramType = readOptionalString(in);
    return new IngressRule(
        host,
        path,
        prefix,
        kind,
        serviceName,
        deploymentName,
        portName,
        interfaceName,
        majorVersion,
        methodName,
        paramType);
  }

  public static void writeAlertRuleSpec(DataOutputStream out, AlertRuleSpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    writeOptionalString(out, spec.tenantId());
    out.writeUTF(spec.deploymentName());
    out.writeUTF(spec.metric().name());
    out.writeUTF(spec.comparator().name());
    out.writeDouble(spec.threshold());
    out.writeUTF(spec.webhookUrl());
    out.writeBoolean(spec.enabled());
  }

  public static AlertRuleSpec readAlertRuleSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    String deploymentName = in.readUTF();
    AlertRuleSpec.Metric metric = AlertRuleSpec.Metric.valueOf(in.readUTF());
    AlertRuleSpec.Comparator comparator = AlertRuleSpec.Comparator.valueOf(in.readUTF());
    double threshold = in.readDouble();
    String webhookUrl = in.readUTF();
    boolean enabled = in.readBoolean();
    return new AlertRuleSpec(
        name, tenantId, deploymentName, metric, comparator, threshold, webhookUrl, enabled);
  }

  private static void writeOptionalStringSet(DataOutputStream out, Optional<Set<String>> values)
      throws IOException {
    out.writeBoolean(values.isPresent());
    if (values.isPresent()) {
      out.writeInt(values.get().size());
      for (String value : values.get()) {
        out.writeUTF(value);
      }
    }
  }

  private static Optional<Set<String>> readOptionalStringSet(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    int count = in.readInt();
    Set<String> values = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      values.add(in.readUTF());
    }
    return Optional.of(values);
  }

  public static void writeLimitRangeSpec(DataOutputStream out, LimitRangeSpec spec)
      throws IOException {
    out.writeUTF(spec.tenantId());
    writeOptionalResourceSpec(out, spec.minRequest());
    writeOptionalResourceSpec(out, spec.maxRequest());
    writeOptionalResourceSpec(out, spec.minLimit());
    writeOptionalResourceSpec(out, spec.maxLimit());
  }

  public static LimitRangeSpec readLimitRangeSpec(DataInputStream in) throws IOException {
    String tenantId = in.readUTF();
    Optional<ResourceSpec> minRequest = readOptionalResourceSpec(in);
    Optional<ResourceSpec> maxRequest = readOptionalResourceSpec(in);
    Optional<ResourceSpec> minLimit = readOptionalResourceSpec(in);
    Optional<ResourceSpec> maxLimit = readOptionalResourceSpec(in);
    return new LimitRangeSpec(tenantId, minRequest, maxRequest, minLimit, maxLimit);
  }

  private static void writeOptionalResourceSpec(DataOutputStream out, Optional<ResourceSpec> spec)
      throws IOException {
    out.writeBoolean(spec.isPresent());
    if (spec.isPresent()) {
      writeResourceSpec(out, spec.get());
    }
  }

  private static Optional<ResourceSpec> readOptionalResourceSpec(DataInputStream in)
      throws IOException {
    return in.readBoolean() ? Optional.of(readResourceSpec(in)) : Optional.empty();
  }

  public static void writeJobSpec(DataOutputStream out, JobSpec spec) throws IOException {
    out.writeUTF(spec.name());
    writeModuleId(out, spec.moduleId());
    out.writeUTF(spec.artifactPath());
    writePlacementConstraints(out, spec.placement());
    writeOptionalDuration(out, spec.activeDeadline());
    out.writeInt(spec.backoffLimit());
    writeOptionalString(out, spec.tenantId());
    writeOptionalString(out, spec.artifactSha256());
    writeOptionalVesselSpec(out, spec.vessel());
  }

  public static JobSpec readJobSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<Duration> activeDeadline = readOptionalDuration(in);
    int backoffLimit = in.readInt();
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> artifactSha256 = readOptionalString(in);
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    return new JobSpec(
        name,
        moduleId,
        artifactPath,
        placement,
        activeDeadline,
        backoffLimit,
        tenantId,
        artifactSha256,
        vessel);
  }

  public static void writeJobRun(DataOutputStream out, JobRun run) throws IOException {
    out.writeUTF(run.jobName());
    out.writeInt(run.attempt());
    out.writeUTF(run.nodeId());
    writeModuleId(out, run.moduleId());
    out.writeUTF(run.artifactPath());
    out.writeUTF(run.startedAt().toString());
    writeOptionalString(out, run.tenantId());
  }

  public static JobRun readJobRun(DataInputStream in) throws IOException {
    String jobName = in.readUTF();
    int attempt = in.readInt();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    Instant startedAt = Instant.parse(in.readUTF());
    Optional<String> tenantId = readOptionalString(in);
    return new JobRun(jobName, attempt, nodeId, moduleId, artifactPath, startedAt, tenantId);
  }

  public static void writeJobRunSummary(DataOutputStream out, JobRunSummary summary)
      throws IOException {
    out.writeUTF(summary.jobName());
    out.writeInt(summary.attempt());
    out.writeUTF(summary.nodeId());
    out.writeUTF(summary.reason());
    writeOptionalString(out, summary.tenantId());
  }

  public static JobRunSummary readJobRunSummary(DataInputStream in) throws IOException {
    String jobName = in.readUTF();
    int attempt = in.readInt();
    String nodeId = in.readUTF();
    String reason = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    return new JobRunSummary(jobName, attempt, nodeId, reason, tenantId);
  }

  public static void writeJobTemplate(DataOutputStream out, JobTemplate template)
      throws IOException {
    writeModuleId(out, template.moduleId());
    out.writeUTF(template.artifactPath());
    writePlacementConstraints(out, template.placement());
    writeOptionalDuration(out, template.activeDeadline());
    out.writeInt(template.backoffLimit());
    writeOptionalVesselSpec(out, template.vessel());
  }

  public static JobTemplate readJobTemplate(DataInputStream in) throws IOException {
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<Duration> activeDeadline = readOptionalDuration(in);
    int backoffLimit = in.readInt();
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    return new JobTemplate(moduleId, artifactPath, placement, activeDeadline, backoffLimit, vessel);
  }

  public static void writeCronJobSpec(DataOutputStream out, CronJobSpec spec) throws IOException {
    out.writeUTF(spec.name());
    out.writeUTF(spec.schedule());
    writeJobTemplate(out, spec.jobTemplate());
    writeOptionalDuration(out, spec.startingDeadline());
    out.writeUTF(spec.concurrencyPolicy().name());
    writeOptionalString(out, spec.tenantId());
    out.writeInt(spec.successfulJobsHistoryLimit());
    out.writeInt(spec.failedJobsHistoryLimit());
    out.writeBoolean(spec.suspend());
  }

  public static CronJobSpec readCronJobSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String schedule = in.readUTF();
    JobTemplate jobTemplate = readJobTemplate(in);
    Optional<Duration> startingDeadline = readOptionalDuration(in);
    ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.valueOf(in.readUTF());
    Optional<String> tenantId = readOptionalString(in);
    int successfulJobsHistoryLimit = in.readInt();
    int failedJobsHistoryLimit = in.readInt();
    boolean suspend = in.readBoolean();
    return new CronJobSpec(
        name,
        schedule,
        jobTemplate,
        startingDeadline,
        concurrencyPolicy,
        tenantId,
        successfulJobsHistoryLimit,
        failedJobsHistoryLimit,
        suspend);
  }

  public static void writeDaemonSetSpec(DataOutputStream out, DaemonSetSpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    writeModuleId(out, spec.moduleId());
    out.writeUTF(spec.artifactPath());
    writePlacementConstraints(out, spec.placement());
    writeOptionalString(out, spec.tenantId());
    writeOptionalString(out, spec.artifactSha256());
    writeOptionalDisruptionBudget(out, spec.disruption());
    writeOptionalVesselSpec(out, spec.vessel());
    out.writeBoolean(spec.tolerateAllTaints());
  }

  public static DaemonSetSpec readDaemonSetSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> artifactSha256 = readOptionalString(in);
    Optional<DisruptionBudget> disruption = readOptionalDisruptionBudget(in);
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    boolean tolerateAllTaints = in.readBoolean();
    return new DaemonSetSpec(
        name,
        moduleId,
        artifactPath,
        placement,
        tenantId,
        artifactSha256,
        disruption,
        vessel,
        tolerateAllTaints);
  }

  public static void writeDaemonSetAssignment(DataOutputStream out, DaemonSetAssignment assignment)
      throws IOException {
    out.writeUTF(assignment.daemonSetName());
    out.writeUTF(assignment.nodeId());
    writeModuleId(out, assignment.moduleId());
    out.writeUTF(assignment.artifactPath());
    writeOptionalString(out, assignment.tenantId());
  }

  public static DaemonSetAssignment readDaemonSetAssignment(DataInputStream in) throws IOException {
    String daemonSetName = in.readUTF();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    return new DaemonSetAssignment(daemonSetName, nodeId, moduleId, artifactPath, tenantId);
  }

  public static void writeStatefulSetSpec(DataOutputStream out, StatefulSetSpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    writeModuleId(out, spec.moduleId());
    out.writeUTF(spec.artifactPath());
    out.writeInt(spec.replicas());
    writePlacementConstraints(out, spec.placement());
    writeOptionalAutoscalePolicy(out, spec.autoscale());
    writeOptionalString(out, spec.tenantId());
    writeOptionalString(out, spec.artifactSha256());
    writeOptionalDisruptionBudget(out, spec.disruption());
    writeOptionalVesselSpec(out, spec.vessel());
  }

  public static StatefulSetSpec readStatefulSetSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    int replicas = in.readInt();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<AutoscalePolicy> autoscale = readOptionalAutoscalePolicy(in);
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> artifactSha256 = readOptionalString(in);
    Optional<DisruptionBudget> disruption = readOptionalDisruptionBudget(in);
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    return new StatefulSetSpec(
        name,
        moduleId,
        artifactPath,
        replicas,
        placement,
        autoscale,
        tenantId,
        artifactSha256,
        disruption,
        vessel);
  }

  public static void writeStatefulSetAssignment(
      DataOutputStream out, StatefulSetAssignment assignment) throws IOException {
    out.writeUTF(assignment.statefulSetName());
    out.writeInt(assignment.instanceIndex());
    out.writeUTF(assignment.nodeId());
    writeModuleId(out, assignment.moduleId());
    out.writeUTF(assignment.artifactPath());
    writeOptionalString(out, assignment.tenantId());
  }

  public static StatefulSetAssignment readStatefulSetAssignment(DataInputStream in)
      throws IOException {
    String statefulSetName = in.readUTF();
    int instanceIndex = in.readInt();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    return new StatefulSetAssignment(
        statefulSetName, instanceIndex, nodeId, moduleId, artifactPath, tenantId);
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
    out.writeInt(pc.priority());
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
    int priority = in.readInt();
    return new PlacementConstraints(labels, antiAffinity, priority);
  }

  /**
   * Bug fixed here (found via a real-cluster QA session driving genuine request-rate load through a
   * deployed instance): the three optional multi-signal fields Part C added to {@link
   * AutoscalePolicy} (targetRequestRatePerSecond/targetErrorRatePercent/targetQueueDepth) were
   * never written here, so any policy configuring one of them was silently truncated to just its
   * CPU target the instant it crossed either wire this codec backs -- {@code StoreClient.propose}
   * on the way in, and every Raft log/snapshot replication after that. Part C's own reconciler
   * tests never caught this because they construct {@code AutoscaleReconciler} against a bare
   * in-process {@code StateStore} (bypassing both {@code StoreCodec} and {@code RaftCodec}
   * entirely), so a `spec.autoscale()` object passed in was always the exact same Java reference
   * read back, never round-tripped through bytes at all.
   */
  public static void writeOptionalAutoscalePolicy(
      DataOutputStream out, Optional<AutoscalePolicy> policy) throws IOException {
    out.writeBoolean(policy.isPresent());
    if (policy.isPresent()) {
      AutoscalePolicy p = policy.get();
      out.writeInt(p.minReplicas());
      out.writeInt(p.maxReplicas());
      out.writeInt(p.targetCpuUtilizationPercent());
      writeOptionalDouble(out, p.targetRequestRatePerSecond());
      writeOptionalDouble(out, p.targetErrorRatePercent());
      writeOptionalInt(out, p.targetQueueDepth());
      // combinationMode + the four per-signal weights -- added in the same
      // change that added the fields themselves, unlike the three multi-signal fields above,
      // which is exactly what this method's own bug-fix history two paragraphs up warns against
      // repeating: any new AutoscalePolicy field belongs here the moment it's added, not later.
      out.writeUTF(p.combinationMode().name());
      writeOptionalDouble(out, p.cpuWeight());
      writeOptionalDouble(out, p.requestRateWeight());
      writeOptionalDouble(out, p.errorRateWeight());
      writeOptionalDouble(out, p.queueDepthWeight());
      out.writeLong(p.scaleUpCooldown().toMillis());
      out.writeLong(p.scaleDownCooldown().toMillis());
    }
  }

  public static Optional<AutoscalePolicy> readOptionalAutoscalePolicy(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    int minReplicas = in.readInt();
    int maxReplicas = in.readInt();
    int targetCpuUtilizationPercent = in.readInt();
    OptionalDouble targetRequestRatePerSecond = readOptionalDouble(in);
    OptionalDouble targetErrorRatePercent = readOptionalDouble(in);
    OptionalInt targetQueueDepth = readOptionalInt(in);
    AutoscalePolicy.CombinationMode combinationMode =
        AutoscalePolicy.CombinationMode.valueOf(in.readUTF());
    OptionalDouble cpuWeight = readOptionalDouble(in);
    OptionalDouble requestRateWeight = readOptionalDouble(in);
    OptionalDouble errorRateWeight = readOptionalDouble(in);
    OptionalDouble queueDepthWeight = readOptionalDouble(in);
    Duration scaleUpCooldown = Duration.ofMillis(in.readLong());
    Duration scaleDownCooldown = Duration.ofMillis(in.readLong());
    return Optional.of(
        new AutoscalePolicy(
            minReplicas,
            maxReplicas,
            targetCpuUtilizationPercent,
            targetRequestRatePerSecond,
            targetErrorRatePercent,
            targetQueueDepth,
            combinationMode,
            cpuWeight,
            requestRateWeight,
            errorRateWeight,
            queueDepthWeight,
            scaleUpCooldown,
            scaleDownCooldown));
  }

  public static void writeOptionalDisruptionBudget(
      DataOutputStream out, Optional<DisruptionBudget> budget) throws IOException {
    out.writeBoolean(budget.isPresent());
    if (budget.isPresent()) {
      out.writeInt(budget.get().maxUnavailable());
      out.writeInt(budget.get().maxSurge());
    }
  }

  public static Optional<DisruptionBudget> readOptionalDisruptionBudget(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    int maxUnavailable = in.readInt();
    int maxSurge = in.readInt();
    return Optional.of(new DisruptionBudget(maxUnavailable, maxSurge));
  }

  /**
   * {@link VesselSpec}'s own wire shape: every list/map field length-prefixed the same way {@link
   * #writePlacementConstraints}'s label set already is, the env-value union tagged with a single
   * leading byte, and the probe ladder's two rungs each a presence flag plus a one-byte kind tag
   * ({@code 0} = TCP, {@code 1} = HTTP) -- {@code Tcp}/{@code Http} don't need a third {@code
   * ProcessAlive} tag here, since "absent" already carries that meaning on the wire exactly as it
   * does in {@link VesselProbes} itself.
   */
  public static void writeOptionalVesselSpec(DataOutputStream out, Optional<VesselSpec> vessel)
      throws IOException {
    out.writeBoolean(vessel.isPresent());
    if (vessel.isEmpty()) {
      return;
    }
    VesselSpec v = vessel.get();
    out.writeInt(v.args().size());
    for (String arg : v.args()) {
      out.writeUTF(arg);
    }
    out.writeInt(v.jvmFlags().size());
    for (String flag : v.jvmFlags()) {
      out.writeUTF(flag);
    }
    out.writeInt(v.env().size());
    for (var entry : v.env().entrySet()) {
      out.writeUTF(entry.getKey());
      writeVesselEnvValue(out, entry.getValue());
    }
    out.writeInt(v.files().size());
    for (var file : v.files()) {
      out.writeUTF(file.path());
      // One byte discriminates the mount's source kind; exactly one key follows either way.
      boolean secretBacked = file.secretKey().isPresent();
      out.writeBoolean(secretBacked);
      out.writeUTF(secretBacked ? file.secretKey().orElseThrow() : file.configKey().orElseThrow());
    }
    writeOptionalVesselProbeSpec(out, v.probes().liveness());
    writeOptionalVesselProbeSpec(out, v.probes().readiness());
    writeResourceSpec(out, v.resourceRequest());
    writeResourceSpec(out, v.resourceLimit());
  }

  public static Optional<VesselSpec> readOptionalVesselSpec(DataInputStream in) throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    int argCount = in.readInt();
    List<String> args = new ArrayList<>();
    for (int i = 0; i < argCount; i++) {
      args.add(in.readUTF());
    }
    int flagCount = in.readInt();
    List<String> jvmFlags = new ArrayList<>();
    for (int i = 0; i < flagCount; i++) {
      jvmFlags.add(in.readUTF());
    }
    int envCount = in.readInt();
    LinkedHashMap<String, VesselEnvValue> env = new LinkedHashMap<>();
    for (int i = 0; i < envCount; i++) {
      String key = in.readUTF();
      env.put(key, readVesselEnvValue(in));
    }
    int fileCount = in.readInt();
    List<VesselFileMount> files = new ArrayList<>();
    for (int i = 0; i < fileCount; i++) {
      String path = in.readUTF();
      boolean secretBacked = in.readBoolean();
      String key = in.readUTF();
      files.add(
          new VesselFileMount(
              path,
              secretBacked ? Optional.empty() : Optional.of(key),
              secretBacked ? Optional.of(key) : Optional.empty()));
    }
    Optional<VesselProbeSpec> liveness = readOptionalVesselProbeSpec(in);
    Optional<VesselProbeSpec> readiness = readOptionalVesselProbeSpec(in);
    VesselProbes probes = new VesselProbes(liveness, readiness);
    ResourceSpec request = readResourceSpec(in);
    ResourceSpec limit = readResourceSpec(in);
    return Optional.of(new VesselSpec(args, jvmFlags, env, files, probes, request, limit));
  }

  private static void writeVesselEnvValue(DataOutputStream out, VesselEnvValue value)
      throws IOException {
    switch (value) {
      case VesselEnvValue.Literal literal -> {
        out.writeByte(0);
        out.writeUTF(literal.value());
      }
      case VesselEnvValue.SecretRef secretRef -> {
        out.writeByte(1);
        out.writeUTF(secretRef.key());
      }
      case VesselEnvValue.PortAllocation portAllocation -> {
        out.writeByte(2);
        writeOptionalInt(out, portAllocation.fixedPort());
      }
      case VesselEnvValue.VolumeMount volumeMount -> {
        out.writeByte(3);
        out.writeLong(volumeMount.sizeBytes());
        out.writeUTF(volumeMount.reclaimPolicy().name());
      }
    }
  }

  private static VesselEnvValue readVesselEnvValue(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    return switch (tag) {
      case 0 -> new VesselEnvValue.Literal(in.readUTF());
      case 1 -> new VesselEnvValue.SecretRef(in.readUTF());
      case 2 -> new VesselEnvValue.PortAllocation(readOptionalInt(in));
      case 3 -> new VesselEnvValue.VolumeMount(in.readLong(), ReclaimPolicy.valueOf(in.readUTF()));
      default -> throw new IllegalStateException("unknown vessel env value tag: " + tag);
    };
  }

  private static void writeOptionalVesselProbeSpec(
      DataOutputStream out, Optional<VesselProbeSpec> probe) throws IOException {
    out.writeBoolean(probe.isPresent());
    if (probe.isEmpty()) {
      return;
    }
    switch (probe.get()) {
      case VesselProbeSpec.Tcp tcp -> {
        out.writeByte(0);
        out.writeInt(tcp.initialDelaySeconds());
        writeOptionalString(out, tcp.portName());
      }
      case VesselProbeSpec.Http http -> {
        out.writeByte(1);
        out.writeInt(http.initialDelaySeconds());
        out.writeUTF(http.path());
        writeOptionalString(out, http.portName());
      }
    }
  }

  private static Optional<VesselProbeSpec> readOptionalVesselProbeSpec(DataInputStream in)
      throws IOException {
    if (!in.readBoolean()) {
      return Optional.empty();
    }
    int tag = in.readUnsignedByte();
    int initialDelaySeconds = in.readInt();
    return switch (tag) {
      case 0 -> Optional.of(new VesselProbeSpec.Tcp(readOptionalString(in), initialDelaySeconds));
      case 1 -> {
        String path = in.readUTF();
        yield Optional.of(
            new VesselProbeSpec.Http(path, readOptionalString(in), initialDelaySeconds));
      }
      default -> throw new IllegalStateException("unknown vessel probe tag: " + tag);
    };
  }

  private static void writeResourceSpec(DataOutputStream out, ResourceSpec spec)
      throws IOException {
    out.writeUTF(spec.memory());
    out.writeUTF(spec.cpu());
  }

  private static ResourceSpec readResourceSpec(DataInputStream in) throws IOException {
    return new ResourceSpec(in.readUTF(), in.readUTF());
  }

  public static void writeOptionalDouble(DataOutputStream out, OptionalDouble value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeDouble(value.getAsDouble());
    }
  }

  public static OptionalDouble readOptionalDouble(DataInputStream in) throws IOException {
    return in.readBoolean() ? OptionalDouble.of(in.readDouble()) : OptionalDouble.empty();
  }

  public static void writeOptionalInt(DataOutputStream out, OptionalInt value) throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeInt(value.getAsInt());
    }
  }

  public static OptionalInt readOptionalInt(DataInputStream in) throws IOException {
    return in.readBoolean() ? OptionalInt.of(in.readInt()) : OptionalInt.empty();
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

  /**
   * The embedded {@code spec} is written by dispatching on its concrete type to whichever of {@link
   * #writeDeploymentSpec}/{@link #writeStatefulSetSpec}/{@link #writeDaemonSetSpec} already exists
   * for that kind -- a leading tag byte records which one so {@link #readControllerRevision} knows
   * which reader to call.
   */
  public static void writeControllerRevision(DataOutputStream out, ControllerRevision revision)
      throws IOException {
    out.writeUTF(revision.workloadKind());
    out.writeUTF(revision.name());
    out.writeInt(revision.revision());
    switch (revision.spec()) {
      case DeploymentSpec s -> {
        out.writeByte(0);
        writeDeploymentSpec(out, s);
      }
      case StatefulSetSpec s -> {
        out.writeByte(1);
        writeStatefulSetSpec(out, s);
      }
      case DaemonSetSpec s -> {
        out.writeByte(2);
        writeDaemonSetSpec(out, s);
      }
      default ->
          throw new IllegalStateException(
              "ControllerRevision cannot embed a " + revision.spec().getClass());
    }
    out.writeLong(revision.createdAtEpochMilli());
    writeOptionalInt(out, revision.rollbackOfRevision());
  }

  public static ControllerRevision readControllerRevision(DataInputStream in) throws IOException {
    String workloadKind = in.readUTF();
    String name = in.readUTF();
    int revision = in.readInt();
    WorkloadSpec spec =
        switch (in.readByte()) {
          case 0 -> readDeploymentSpec(in);
          case 1 -> readStatefulSetSpec(in);
          case 2 -> readDaemonSetSpec(in);
          default -> throw new IllegalStateException("unknown ControllerRevision spec tag");
        };
    long createdAtEpochMilli = in.readLong();
    OptionalInt rollbackOfRevision = readOptionalInt(in);
    return new ControllerRevision(
        workloadKind, name, revision, spec, createdAtEpochMilli, rollbackOfRevision);
  }

  public static void writeInstanceAssignment(DataOutputStream out, InstanceAssignment assignment)
      throws IOException {
    out.writeUTF(assignment.deploymentName());
    out.writeInt(assignment.instanceIndex());
    out.writeUTF(assignment.nodeId());
    writeModuleId(out, assignment.moduleId());
    out.writeUTF(assignment.artifactPath());
    writeOptionalInt(out, assignment.renamedFromInstanceIndex());
    writeOptionalString(out, assignment.tenantId());
  }

  public static InstanceAssignment readInstanceAssignment(DataInputStream in) throws IOException {
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    String nodeId = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    OptionalInt renamedFromInstanceIndex = readOptionalInt(in);
    Optional<String> tenantId = readOptionalString(in);
    return new InstanceAssignment(
        deploymentName,
        instanceIndex,
        nodeId,
        moduleId,
        artifactPath,
        renamedFromInstanceIndex,
        tenantId);
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
    // Written separately from the self-reported half above so a re-registering node replaces only
    // its own labels, never the ones an operator applied to it.
    Set<String> operatorLabels = registration.operatorLabels();
    out.writeInt(operatorLabels.size());
    for (String label : operatorLabels) {
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
    Set<String> operatorLabels = new LinkedHashSet<>();
    int operatorLabelCount = in.readInt();
    for (int i = 0; i < operatorLabelCount; i++) {
      operatorLabels.add(in.readUTF());
    }
    return new NodeRegistration(
        nodeId,
        new NodeCapabilities(tiers, labels),
        apiAddress.isEmpty() ? Optional.empty() : Optional.of(apiAddress),
        operatorLabels);
  }

  public static void writeTenant(DataOutputStream out, Tenant tenant) throws IOException {
    out.writeUTF(tenant.id());
    out.writeLong(tenant.quota().maxMemoryBytes());
    out.writeLong(tenant.quota().maxCpuMillicores());
    out.writeInt(tenant.quota().maxInstances());
    out.writeUTF(tenant.isolationPosture().name());
  }

  public static Tenant readTenant(DataInputStream in) throws IOException {
    String id = in.readUTF();
    long maxMemoryBytes = in.readLong();
    long maxCpuMillicores = in.readLong();
    int maxInstances = in.readInt();
    TenantIsolationPosture isolationPosture = TenantIsolationPosture.valueOf(in.readUTF());
    return new Tenant(
        id, new ResourceQuota(maxMemoryBytes, maxCpuMillicores, maxInstances), isolationPosture);
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
    // Both positions carry a wildcard as Permission's own token spelling rather than an enum name,
    // so a wildcard grant is one string here exactly like a named one -- the widening happens when
    // a request is matched, never at rest.
    out.writeUTF(permission.resourceToken());
    out.writeUTF(permission.verbToken());
    writeOptionalString(out, permission.tenantScope());
    writeOptionalString(out, permission.qualifier());
  }

  public static Permission readPermission(DataInputStream in) throws IOException {
    Optional<ResourceKind> resource = Permission.parseResource(in.readUTF());
    Optional<Verb> verb = Permission.parseVerb(in.readUTF());
    Optional<String> tenantScope = readOptionalString(in);
    Optional<String> qualifier = readOptionalString(in);
    return new Permission(resource, verb, tenantScope, qualifier);
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
    out.writeInt(account.groups().size());
    for (String group : account.groups()) {
      out.writeUTF(group);
    }
  }

  public static Account readAccount(DataInputStream in) throws IOException {
    String username = in.readUTF();
    byte[] passwordHash = readBytes(in);
    int groupCount = in.readInt();
    Set<String> groups = new LinkedHashSet<>();
    for (int i = 0; i < groupCount; i++) {
      groups.add(in.readUTF());
    }
    return new Account(username, passwordHash, groups);
  }

  // NodeHeartbeat/ObservedHeartbeat: never Raft-replicated (RaftCodec never needed these), but
  // travel over StoreRpc's PutHeartbeat/GetNodeHeartbeat -- the one write that stays leader-only
  // but non-replicated, same as today.
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

  public static void writeWorkloadTokenRecord(DataOutputStream out, WorkloadTokenRecord record)
      throws IOException {
    out.writeUTF(record.key());
    out.writeUTF(record.tokenSha256Hex());
    out.writeBoolean(record.tenantId().isPresent());
    if (record.tenantId().isPresent()) {
      out.writeUTF(record.tenantId().get());
    }
    out.writeUTF(record.deploymentName());
    out.writeLong(record.expiresAtEpochMilli());
  }

  public static WorkloadTokenRecord readWorkloadTokenRecord(DataInputStream in) throws IOException {
    String key = in.readUTF();
    String tokenSha256Hex = in.readUTF();
    Optional<String> tenantId = in.readBoolean() ? Optional.of(in.readUTF()) : Optional.empty();
    String deploymentName = in.readUTF();
    long expiresAtEpochMilli = in.readLong();
    return new WorkloadTokenRecord(
        key, tokenSha256Hex, tenantId, deploymentName, expiresAtEpochMilli);
  }

  /**
   * {@code responseBody} goes through {@link #writeBytes} rather than {@code writeUTF}: a recorded
   * API response is arbitrary JSON, and {@code writeUTF}'s 64KiB ceiling would turn a large one
   * into a serialization failure at propose time.
   */
  public static void writeRequestOutcomeRecord(DataOutputStream out, RequestOutcomeRecord record)
      throws IOException {
    out.writeUTF(record.requestId());
    out.writeUTF(record.principalName());
    out.writeInt(record.statusCode());
    writeBytes(out, record.responseBody().getBytes(StandardCharsets.UTF_8));
    out.writeLong(record.recordedAtEpochMilli());
  }

  public static RequestOutcomeRecord readRequestOutcomeRecord(DataInputStream in)
      throws IOException {
    String requestId = in.readUTF();
    String principalName = in.readUTF();
    int statusCode = in.readInt();
    String responseBody = new String(readBytes(in), StandardCharsets.UTF_8);
    long recordedAtEpochMilli = in.readLong();
    return new RequestOutcomeRecord(
        requestId, principalName, statusCode, responseBody, recordedAtEpochMilli);
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
    out.writeInt(obs.ports().size());
    for (var entry : obs.ports().entrySet()) {
      out.writeUTF(entry.getKey());
      out.writeInt(entry.getValue());
    }
    out.writeLong(obs.volumeUsageBytes());
    writeOptionalString(out, obs.workerId());
    writeOptionalString(out, obs.tenantId());
    writeOptionalString(out, obs.isolationTier().map(Enum::name));
    writeOptionalResourceSpec(out, obs.resourceLimit());
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
    int portCount = in.readInt();
    LinkedHashMap<String, Integer> ports = new LinkedHashMap<>();
    for (int i = 0; i < portCount; i++) {
      String portName = in.readUTF();
      ports.put(portName, in.readInt());
    }
    long volumeUsageBytes = in.readLong();
    Optional<String> workerId = readOptionalString(in);
    Optional<String> tenantId = readOptionalString(in);
    Optional<IsolationTier> isolationTier = readOptionalString(in).map(IsolationTier::valueOf);
    Optional<ResourceSpec> resourceLimit = readOptionalResourceSpec(in);
    return InstanceObservation.builder(
            deploymentName, instanceIndex, moduleId, lifecycleState, alive, ready)
        .load(
            requestRatePerSecond,
            errorRatePerSecond,
            queueDepth,
            cpuMillicoresUsed,
            memoryBytesUsed)
        .ports(ports)
        .volumeUsageBytes(volumeUsageBytes)
        .workerId(workerId)
        .tenantId(tenantId)
        .isolationTier(isolationTier)
        .resourceLimit(resourceLimit)
        .build();
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
    out.writeLong(state.firstContinuousReadyAtEpochMilli());
    writeOptionalString(out, state.tenantId());
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
    long firstContinuousReadyAtEpochMilli = in.readLong();
    Optional<String> tenantId = readOptionalString(in);
    return new ReconcilerInstanceState(
        deploymentName,
        instanceIndex,
        attemptsInWindow,
        windowStartEpochMilli,
        nextAllowedAttemptEpochMilli,
        pendingRetry,
        permanentlyFailed,
        firstSeenMissingAtEpochMilli,
        firstContinuousReadyAtEpochMilli,
        tenantId);
  }

  public static void writeWorkloadHealthState(DataOutputStream out, WorkloadHealthState state)
      throws IOException {
    out.writeUTF(state.workloadKind());
    out.writeUTF(state.workloadName());
    out.writeUTF(state.slot());
    out.writeInt(state.attemptsInWindow());
    out.writeLong(state.windowStartEpochMilli());
    out.writeLong(state.nextAllowedAttemptEpochMilli());
    out.writeBoolean(state.pendingRetry());
    out.writeBoolean(state.permanentlyFailed());
    out.writeLong(state.firstContinuousReadyAtEpochMilli());
    writeOptionalString(out, state.tenantId());
  }

  public static WorkloadHealthState readWorkloadHealthState(DataInputStream in) throws IOException {
    String workloadKind = in.readUTF();
    String workloadName = in.readUTF();
    String slot = in.readUTF();
    int attemptsInWindow = in.readInt();
    long windowStartEpochMilli = in.readLong();
    long nextAllowedAttemptEpochMilli = in.readLong();
    boolean pendingRetry = in.readBoolean();
    boolean permanentlyFailed = in.readBoolean();
    long firstContinuousReadyAtEpochMilli = in.readLong();
    Optional<String> tenantId = readOptionalString(in);
    return new WorkloadHealthState(
        workloadKind,
        workloadName,
        slot,
        attemptsInWindow,
        windowStartEpochMilli,
        nextAllowedAttemptEpochMilli,
        pendingRetry,
        permanentlyFailed,
        firstContinuousReadyAtEpochMilli,
        tenantId);
  }

  public static void writeInstanceEvent(DataOutputStream out, InstanceEvent event)
      throws IOException {
    out.writeUTF(event.id());
    out.writeUTF(event.deploymentName());
    out.writeInt(event.instanceIndex());
    out.writeUTF(event.kind().name());
    out.writeUTF(event.message());
    out.writeUTF(event.causeSummary().orElse(""));
    out.writeLong(event.occurredAtEpochMilli());
  }

  public static InstanceEvent readInstanceEvent(DataInputStream in) throws IOException {
    String id = in.readUTF();
    String deploymentName = in.readUTF();
    int instanceIndex = in.readInt();
    InstanceEventKind kind = InstanceEventKind.valueOf(in.readUTF());
    String message = in.readUTF();
    String causeSummary = in.readUTF();
    long occurredAtEpochMilli = in.readLong();
    return new InstanceEvent(
        id,
        deploymentName,
        instanceIndex,
        kind,
        message,
        causeSummary.isEmpty() ? Optional.empty() : Optional.of(causeSummary),
        occurredAtEpochMilli);
  }

  public static void writeAuditEvent(DataOutputStream out, AuditEvent event) throws IOException {
    out.writeUTF(event.id());
    out.writeUTF(event.principal());
    out.writeInt(event.groups().size());
    for (String group : event.groups()) {
      out.writeUTF(group);
    }
    out.writeUTF(event.resourceKind());
    out.writeUTF(event.verb());
    writeOptionalString(out, event.tenantId());
    writeOptionalString(out, event.targetId());
    out.writeBoolean(event.allowed());
    out.writeUTF(event.outcome().name());
    out.writeLong(event.occurredAtEpochMilli());
    writeOptionalInt(out, event.version());
  }

  public static AuditEvent readAuditEvent(DataInputStream in) throws IOException {
    String id = in.readUTF();
    String principal = in.readUTF();
    int groupCount = in.readInt();
    Set<String> groups = new LinkedHashSet<>();
    for (int i = 0; i < groupCount; i++) {
      groups.add(in.readUTF());
    }
    String resourceKind = in.readUTF();
    String verb = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> targetId = readOptionalString(in);
    boolean allowed = in.readBoolean();
    AuditOutcome outcome = AuditOutcome.valueOf(in.readUTF());
    long occurredAtEpochMilli = in.readLong();
    OptionalInt version = readOptionalInt(in);
    return new AuditEvent(
        id,
        principal,
        groups,
        resourceKind,
        verb,
        tenantId,
        targetId,
        allowed,
        outcome,
        occurredAtEpochMilli,
        version);
  }

  public static void writeAuditTrailStatus(DataOutputStream out, AuditTrailStatus status)
      throws IOException {
    out.writeInt(status.retainedCount());
    out.writeLong(status.evictedTotal());
    writeOptionalLong(out, status.oldestRetainedAtEpochMilli());
  }

  public static AuditTrailStatus readAuditTrailStatus(DataInputStream in) throws IOException {
    int retainedCount = in.readInt();
    long evictedTotal = in.readLong();
    Optional<Long> oldestRetainedAtEpochMilli = readOptionalLong(in);
    return new AuditTrailStatus(retainedCount, evictedTotal, oldestRetainedAtEpochMilli);
  }

  /**
   * {@code since} filters ({@code StoreRpc.ListAuditEvents}) are the first optional {@code long}
   * this codec has needed to serialize -- {@link #writeOptionalString}/{@link #readOptionalString}
   * cover every optional-{@code String} case so far, this is the same presence-flag shape for the
   * one primitive type they don't already handle.
   */
  public static void writeOptionalLong(DataOutputStream out, Optional<Long> value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeLong(value.get());
    }
  }

  public static Optional<Long> readOptionalLong(DataInputStream in) throws IOException {
    return in.readBoolean() ? Optional.of(in.readLong()) : Optional.empty();
  }

  /**
   * {@link JobSpec#activeDeadline()}'s own wire shape -- whole seconds, via {@link
   * #writeOptionalLong}, matching {@code JobManifestParser#parseActiveDeadline}'s own
   * seconds-granularity YAML field exactly.
   */
  public static void writeOptionalDuration(DataOutputStream out, Optional<Duration> value)
      throws IOException {
    writeOptionalLong(out, value.map(Duration::toSeconds));
  }

  public static Optional<Duration> readOptionalDuration(DataInputStream in) throws IOException {
    return readOptionalLong(in).map(Duration::ofSeconds);
  }

  // ---- custom kinds (Galdr) ----

  private static final byte SCHEMA_FIELD_STRING = 0;
  private static final byte SCHEMA_FIELD_INT = 1;
  private static final byte SCHEMA_FIELD_DOUBLE = 2;
  private static final byte SCHEMA_FIELD_BOOL = 3;
  private static final byte SCHEMA_FIELD_ENUM = 4;
  private static final byte SCHEMA_FIELD_LIST = 5;
  private static final byte SCHEMA_FIELD_OBJECT = 6;

  public static void writeKindDefinitionSpec(DataOutputStream out, KindDefinitionSpec spec)
      throws IOException {
    out.writeUTF(spec.kindName());
    out.writeUTF(spec.scope().name());
    out.writeUTF(spec.description());
    writeOptionalString(out, spec.names().plural());
    out.writeInt(spec.names().shortNames().size());
    for (String shortName : spec.names().shortNames()) {
      out.writeUTF(shortName);
    }
    writeSchemaFieldList(out, spec.schema().fields());
    out.writeInt(spec.printColumns().size());
    for (PrintColumn column : spec.printColumns()) {
      out.writeUTF(column.name());
      out.writeUTF(column.path());
    }
    out.writeLong(spec.generation());
  }

  public static KindDefinitionSpec readKindDefinitionSpec(DataInputStream in) throws IOException {
    String kindName = in.readUTF();
    KindScope scope = KindScope.valueOf(in.readUTF());
    String description = in.readUTF();
    Optional<String> plural = readOptionalString(in);
    int shortNameCount = in.readInt();
    List<String> shortNames = new ArrayList<>();
    for (int i = 0; i < shortNameCount; i++) {
      shortNames.add(in.readUTF());
    }
    List<SchemaField> fields = readSchemaFieldList(in);
    int printColumnCount = in.readInt();
    List<PrintColumn> printColumns = new ArrayList<>();
    for (int i = 0; i < printColumnCount; i++) {
      printColumns.add(new PrintColumn(in.readUTF(), in.readUTF()));
    }
    long generation = in.readLong();
    return new KindDefinitionSpec(
        kindName,
        scope,
        description,
        new KindNames(plural, shortNames),
        new SchemaModel(fields),
        printColumns,
        generation);
  }

  private static void writeSchemaFieldList(DataOutputStream out, List<SchemaField> fields)
      throws IOException {
    out.writeInt(fields.size());
    for (SchemaField field : fields) {
      writeSchemaField(out, field);
    }
  }

  private static List<SchemaField> readSchemaFieldList(DataInputStream in) throws IOException {
    int count = in.readInt();
    List<SchemaField> fields = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      fields.add(readSchemaField(in));
    }
    return fields;
  }

  /**
   * Recursive over {@link SchemaField.ListField}'s item schema and {@link
   * SchemaField.ObjectField}'s nested fields -- recursion depth is bounded by the schema depth cap
   * {@code SchemaValidator} enforces before any schema is ever stored, so a legitimately-stored
   * schema can never overflow this reader's stack.
   */
  private static void writeSchemaField(DataOutputStream out, SchemaField field) throws IOException {
    switch (field) {
      case SchemaField.StringField f -> {
        out.writeByte(SCHEMA_FIELD_STRING);
        out.writeUTF(f.name());
        out.writeBoolean(f.required());
        writeOptionalString(out, f.defaultValue());
        writeOptionalInt(out, f.maxLength());
      }
      case SchemaField.IntField f -> {
        out.writeByte(SCHEMA_FIELD_INT);
        out.writeUTF(f.name());
        out.writeBoolean(f.required());
        writeOptionalLongValue(out, f.defaultValue());
        writeOptionalLongValue(out, f.min());
        writeOptionalLongValue(out, f.max());
      }
      case SchemaField.DoubleField f -> {
        out.writeByte(SCHEMA_FIELD_DOUBLE);
        out.writeUTF(f.name());
        out.writeBoolean(f.required());
        writeOptionalDouble(out, f.defaultValue());
        writeOptionalDouble(out, f.min());
        writeOptionalDouble(out, f.max());
      }
      case SchemaField.BoolField f -> {
        out.writeByte(SCHEMA_FIELD_BOOL);
        out.writeUTF(f.name());
        out.writeBoolean(f.required());
        out.writeBoolean(f.defaultValue().isPresent());
        if (f.defaultValue().isPresent()) {
          out.writeBoolean(f.defaultValue().get());
        }
      }
      case SchemaField.EnumField f -> {
        out.writeByte(SCHEMA_FIELD_ENUM);
        out.writeUTF(f.name());
        out.writeBoolean(f.required());
        writeOptionalString(out, f.defaultValue());
        out.writeInt(f.values().size());
        for (String value : f.values()) {
          out.writeUTF(value);
        }
      }
      case SchemaField.ListField f -> {
        out.writeByte(SCHEMA_FIELD_LIST);
        out.writeUTF(f.name());
        writeSchemaField(out, f.items());
        writeOptionalInt(out, f.minItems());
        writeOptionalInt(out, f.maxItems());
      }
      case SchemaField.ObjectField f -> {
        out.writeByte(SCHEMA_FIELD_OBJECT);
        out.writeUTF(f.name());
        writeSchemaFieldList(out, f.fields());
      }
    }
  }

  private static SchemaField readSchemaField(DataInputStream in) throws IOException {
    byte tag = in.readByte();
    return switch (tag) {
      case SCHEMA_FIELD_STRING ->
          new SchemaField.StringField(
              in.readUTF(), in.readBoolean(), readOptionalString(in), readOptionalInt(in));
      case SCHEMA_FIELD_INT ->
          new SchemaField.IntField(
              in.readUTF(),
              in.readBoolean(),
              readOptionalLongValue(in),
              readOptionalLongValue(in),
              readOptionalLongValue(in));
      case SCHEMA_FIELD_DOUBLE ->
          new SchemaField.DoubleField(
              in.readUTF(),
              in.readBoolean(),
              readOptionalDouble(in),
              readOptionalDouble(in),
              readOptionalDouble(in));
      case SCHEMA_FIELD_BOOL -> {
        String name = in.readUTF();
        boolean required = in.readBoolean();
        Optional<Boolean> defaultValue =
            in.readBoolean() ? Optional.of(in.readBoolean()) : Optional.empty();
        yield new SchemaField.BoolField(name, required, defaultValue);
      }
      case SCHEMA_FIELD_ENUM -> {
        String name = in.readUTF();
        boolean required = in.readBoolean();
        Optional<String> defaultValue = readOptionalString(in);
        int valueCount = in.readInt();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < valueCount; i++) {
          values.add(in.readUTF());
        }
        yield new SchemaField.EnumField(name, required, defaultValue, values);
      }
      case SCHEMA_FIELD_LIST -> {
        String name = in.readUTF();
        SchemaField items = readSchemaField(in);
        yield new SchemaField.ListField(name, items, readOptionalInt(in), readOptionalInt(in));
      }
      case SCHEMA_FIELD_OBJECT -> {
        String name = in.readUTF();
        yield new SchemaField.ObjectField(name, readSchemaFieldList(in));
      }
      default -> throw new IllegalArgumentException("unknown schema field tag: " + tag);
    };
  }

  private static void writeOptionalLongValue(DataOutputStream out, OptionalLong value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeLong(value.getAsLong());
    }
  }

  private static OptionalLong readOptionalLongValue(DataInputStream in) throws IOException {
    return in.readBoolean() ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
  }

  public static void writeCustomResource(DataOutputStream out, CustomResource resource)
      throws IOException {
    out.writeUTF(resource.kindName());
    out.writeUTF(resource.name());
    writeOptionalString(out, resource.tenantId());
    writeBytes(out, resource.specJson());
    writeBytes(out, resource.statusJson());
    out.writeLong(resource.generation());
  }

  public static CustomResource readCustomResource(DataInputStream in) throws IOException {
    String kindName = in.readUTF();
    String name = in.readUTF();
    Optional<String> tenantId = readOptionalString(in);
    byte[] specJson = readBytes(in);
    byte[] statusJson = readBytes(in);
    long generation = in.readLong();
    return new CustomResource(kindName, name, tenantId, specJson, statusJson, generation);
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
