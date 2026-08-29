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
import com.gimle.core.module.ReclaimPolicy;
import com.gimle.core.module.ResourceSpec;
import com.gimle.core.module.Version;
import com.gimle.core.protocol.AuditEvent;
import com.gimle.core.protocol.AuditOutcome;
import com.gimle.core.protocol.InstanceEvent;
import com.gimle.core.protocol.InstanceEventKind;
import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeCapabilities;
import com.gimle.core.protocol.NodeHeartbeat;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.core.protocol.ResourceUsageSnapshot;
import com.gimle.core.tenant.ResourceQuota;
import com.gimle.core.tenant.Tenant;
import com.gimle.core.vessel.VesselEnvValue;
import com.gimle.core.vessel.VesselFileMount;
import com.gimle.core.vessel.VesselProbeSpec;
import com.gimle.core.vessel.VesselProbes;
import com.gimle.core.vessel.VesselSpec;
import com.gimle.mimir.manifest.AutoscalePolicy;
import com.gimle.mimir.manifest.ConcurrencyPolicy;
import com.gimle.mimir.manifest.CronJobSpec;
import com.gimle.mimir.manifest.DaemonSetSpec;
import com.gimle.mimir.manifest.DeploymentSpec;
import com.gimle.mimir.manifest.DisruptionBudget;
import com.gimle.mimir.manifest.JobSpec;
import com.gimle.mimir.manifest.JobTemplate;
import com.gimle.mimir.manifest.LimitRangeSpec;
import com.gimle.mimir.manifest.NetworkPolicySpec;
import com.gimle.mimir.manifest.PlacementConstraints;
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
import com.gimle.mimir.store.StatefulSetAssignment;
import com.gimle.mimir.store.WorkloadTokenRecord;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
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
    out.writeInt(spec.targetPort());
    out.writeBoolean(spec.sessionAffinity());
    writeOptionalString(out, spec.externalName());
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
    int targetPort = in.readInt();
    boolean sessionAffinity = in.readBoolean();
    Optional<String> externalName = readOptionalString(in);
    return new ServiceSpec(
        name, tenantId, deploymentNames, port, targetPort, sessionAffinity, externalName);
  }

  public static void writeNetworkPolicySpec(DataOutputStream out, NetworkPolicySpec spec)
      throws IOException {
    out.writeUTF(spec.name());
    out.writeUTF(spec.tenantId());
    writeOptionalStringSet(out, spec.deploymentNames());
    writeOptionalStringSet(out, spec.serviceInterfaceNames());
    writeOptionalStringSet(out, spec.allowedCallerTenantIds());
    writeOptionalStringSet(out, spec.allowedCalleeTenantIds());
  }

  public static NetworkPolicySpec readNetworkPolicySpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String tenantId = in.readUTF();
    Optional<Set<String>> deploymentNames = readOptionalStringSet(in);
    Optional<Set<String>> serviceInterfaceNames = readOptionalStringSet(in);
    Optional<Set<String>> allowedCallerTenantIds = readOptionalStringSet(in);
    Optional<Set<String>> allowedCalleeTenantIds = readOptionalStringSet(in);
    return new NetworkPolicySpec(
        name,
        tenantId,
        deploymentNames,
        serviceInterfaceNames,
        allowedCallerTenantIds,
        allowedCalleeTenantIds);
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
  }

  public static CronJobSpec readCronJobSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    String schedule = in.readUTF();
    JobTemplate jobTemplate = readJobTemplate(in);
    Optional<Duration> startingDeadline = readOptionalDuration(in);
    ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.valueOf(in.readUTF());
    Optional<String> tenantId = readOptionalString(in);
    return new CronJobSpec(
        name, schedule, jobTemplate, startingDeadline, concurrencyPolicy, tenantId);
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
    return new DaemonSetSpec(
        name, moduleId, artifactPath, placement, tenantId, artifactSha256, disruption, vessel);
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
    writeOptionalString(out, spec.tenantId());
    writeOptionalString(out, spec.artifactSha256());
    writeOptionalVesselSpec(out, spec.vessel());
  }

  public static StatefulSetSpec readStatefulSetSpec(DataInputStream in) throws IOException {
    String name = in.readUTF();
    ModuleId moduleId = readModuleId(in);
    String artifactPath = in.readUTF();
    int replicas = in.readInt();
    PlacementConstraints placement = readPlacementConstraints(in);
    Optional<String> tenantId = readOptionalString(in);
    Optional<String> artifactSha256 = readOptionalString(in);
    Optional<VesselSpec> vessel = readOptionalVesselSpec(in);
    return new StatefulSetSpec(
        name, moduleId, artifactPath, replicas, placement, tenantId, artifactSha256, vessel);
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
            queueDepthWeight));
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
      }
      case VesselProbeSpec.Http http -> {
        out.writeByte(1);
        out.writeInt(http.initialDelaySeconds());
        out.writeUTF(http.path());
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
      case 0 -> Optional.of(new VesselProbeSpec.Tcp(initialDelaySeconds));
      case 1 -> Optional.of(new VesselProbeSpec.Http(in.readUTF(), initialDelaySeconds));
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
        errorRatePerSecond,
        ports,
        volumeUsageBytes,
        workerId);
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
        occurredAtEpochMilli);
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
