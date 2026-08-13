export type LifecycleState =
  | "INSTALLED"
  | "RESOLVED"
  | "STARTING"
  | "ACTIVE"
  | "STOPPING"
  | "UNINSTALLED"
  | "FAILED"
  // Run-to-completion success terminal (priority-3 design doc §3b) -- only a Job-kind instance's
  // own observation ever reports this; a Deployment replica's lifecycleState never does.
  | "COMPLETED";

export type Tier = "TIER_1" | "TIER_2" | "TIER_3";

export interface ModuleId {
  name: string;
  version: string;
}

export interface InstanceObservation {
  lifecycleState: LifecycleState;
  alive: boolean;
  ready: boolean;
  requestRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
}

export interface DeploymentInstance {
  instanceIndex: number;
  nodeId: string;
  observation: InstanceObservation;
}

export interface DeploymentSpec {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
  autoscale?: AutoscalePolicy;
}

export interface DeploymentSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
  autoscale?: AutoscalePolicy;
}

export interface Deployment {
  spec: DeploymentSpec;
  instances: DeploymentInstance[];
  unplacedCount: number;
  quotaViolating: boolean;
}

/* ---------------------------------------------------------------------------
 * Jobs (priority-3 design doc §3) -- run-to-completion, not run-forever: no `replicas`/
 * `autoscale`, and a `phase`/`currentRun` in place of Deployment's `instances[]` array, since a
 * Job never has more than one non-terminal attempt at a time.
 * ------------------------------------------------------------------------ */

export type JobPhase = "RUNNING" | "SUCCEEDED" | "FAILED";

export interface JobRun {
  attempt: number;
  nodeId: string;
  observation: InstanceObservation | null;
}

export interface JobSpec {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  /** Wall-clock ceiling across every attempt combined, seconds; absent means no deadline. */
  activeDeadlineSeconds?: number;
  backoffLimit: number;
  tenantId: string | null;
}

export interface JobSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  activeDeadlineSeconds?: number;
  backoffLimit: number;
  tenantId: string | null;
}

export interface Job {
  spec: JobSpec;
  phase: JobPhase;
  currentRun: JobRun | null;
}

/* ---------------------------------------------------------------------------
 * CronJobs (priority-3 design doc §3d) -- a thin schedule generator over Job: no `phase`/
 * `currentRun` of its own (a CronJob is never itself running or terminal), just `lastScheduleTime`
 * plus whatever Job it most recently generated -- the Jobs screen is where that generated Job's
 * own phase/attempt live.
 * ------------------------------------------------------------------------ */

export type ConcurrencyPolicy = "ALLOW" | "FORBID" | "REPLACE";

export interface JobTemplate {
  moduleId: ModuleId;
  artifactPath: string;
  /** Wall-clock ceiling across every generated Job's attempts combined, seconds. */
  activeDeadlineSeconds?: number;
  backoffLimit: number;
}

export interface CronJobSpec {
  name: string;
  /** Standard 5-field cron expression, evaluated in UTC. */
  schedule: string;
  jobTemplate: JobTemplate;
  /** How late a firing may still be honored before it's logged as missed instead, seconds. */
  startingDeadlineSeconds?: number;
  concurrencyPolicy: ConcurrencyPolicy;
  tenantId: string | null;
}

export interface CronJobSpecInput {
  name: string;
  schedule: string;
  jobTemplate: JobTemplate;
  startingDeadlineSeconds?: number;
  concurrencyPolicy: ConcurrencyPolicy;
  tenantId: string | null;
}

export interface CronJob {
  spec: CronJobSpec;
  /** Absent means this CronJob has never fired yet. */
  lastScheduleTime: string | null;
}

/* ---------------------------------------------------------------------------
 * DaemonSets (priority-3 design doc §4d) -- one instance per eligible node, not an
 * operator-settable replica count: no `replicas`/`autoscale` field, and `instances[]` entries carry
 * only `nodeId` (never `instanceIndex` -- there's always exactly one per node, so there's nothing
 * for an index to distinguish). `requiredNodeLabels` is promoted to a primary field here, unlike
 * Deployment's placement fields (not yet surfaced in the console at all): a DaemonSet's whole
 * purpose is "run on nodes matching this shape," so it's the field an operator most needs to see.
 * ------------------------------------------------------------------------ */

export interface DaemonSetPlacement {
  requiredNodeLabels: string[];
}

export interface DaemonSetSpec {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  placement: DaemonSetPlacement;
  tenantId: string | null;
}

export interface DaemonSetSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  placement: DaemonSetPlacement;
  tenantId: string | null;
}

export interface DaemonSetInstance {
  nodeId: string;
  observation: InstanceObservation;
}

export interface DaemonSet {
  spec: DaemonSetSpec;
  instances: DaemonSetInstance[];
}

/* ---------------------------------------------------------------------------
 * StatefulSets (priority-3 design doc §5) -- the last workload-diversity kind: stable per-index
 * identity plus (optionally, declared on the module's own artifact, not modeled here) persistent
 * local-disk storage. `instances[]` carries a real `instanceIndex` like Deployment's own (never a
 * fixed 0 the way DaemonSet's does), since a StatefulSet index is a stable, individually-addressed
 * identity. No `autoscale` -- unlike Deployment, a StatefulSet's replica count is never
 * autoscaler-managed.
 * ------------------------------------------------------------------------ */

export interface StatefulSetSpec {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
}

export interface StatefulSetSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
}

export interface StatefulSetInstance {
  instanceIndex: number;
  nodeId: string;
  observation: InstanceObservation;
}

export interface StatefulSet {
  spec: StatefulSetSpec;
  instances: StatefulSetInstance[];
  unplacedCount: number;
}

export interface Node {
  nodeId: string;
  capabilities: { supportedTiers: Tier[] };
  lastHeartbeatAt: string | null;
  capacity: {
    totalMemoryBytes: number;
    assignedMemoryBytes: number;
    totalCpuMillicores: number;
    assignedCpuMillicores: number;
  };
}

export interface Tenant {
  id: string;
  quota: {
    maxMemoryBytes: number;
    maxCpuMillicores: number;
    maxInstances: number;
  };
}

export interface ConfigEntry {
  tenantId: string;
  key: string;
  value: string;
  encrypted: boolean;
}

// Fafnir's /secrets/* surface (design doc §6e/§7) never returns a value alongside metadata --
// SecretMetadata and SecretValue are deliberately two separate types, not one type with an
// optional `value`, so a list response can't accidentally be typed as if it carried one.
export interface SecretMetadata {
  tenantId: string;
  key: string;
  latestVersion: number;
  deleted: boolean;
}

export interface SecretValue {
  tenantId: string;
  key: string;
  version: number;
  value: string;
}

export interface ModuleInstance {
  deploymentName: string;
  instanceIndex: number;
  moduleId: ModuleId;
  artifactPath: string;
  tenantId: string | null;
  nodeId: string;
  lifecycleState: LifecycleState;
  alive: boolean;
  ready: boolean;
  requestRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
}

export type LogLevel = "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";
export type LogCategory = "APPLICATION" | "PLATFORM" | "SYSTEM";
export type ProcessRole = "WORKER" | "CONTROLLER" | "NODE";

export interface StructuredLogLine {
  timestamp: string;
  level: LogLevel;
  logger: string;
  thread: string;
  message: string;
  category: LogCategory;
  processRole: ProcessRole;
  nodeId: string;
  moduleId?: string;
  moduleVersion?: string;
  deploymentName?: string;
  instanceIndex?: number;
  tenantId?: string;
}

export interface RawLogLine {
  timestamp: string;
  category: "SYSTEM";
  raw: string;
}

export type LogLine = StructuredLogLine | RawLogLine;

export type LogTarget =
  | {
      kind: "instance";
      deploymentName: string;
      instanceIndex: number;
      category: "APPLICATION" | "PLATFORM";
    }
  | { kind: "node"; nodeId: string; category: "PLATFORM" | "SYSTEM" }
  | { kind: "controlplane"; category: "PLATFORM" | "SYSTEM" };

/** hs_err_pid*.log crash dumps -- a directory listing, not a log stream, so this is its own
 * shape rather than forced into LogLine/Page (see AgentLogServer's crashdumps route). */
export interface CrashDump {
  name: string;
  sizeBytes: number;
  lastModified: string;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface Principal {
  username: string;
  groups: string[];
}

/* ---------------------------------------------------------------------------
 * Process-scoped history (metrics + traces) -- GET /metrics-history/*, GET /traces-history/*
 * ------------------------------------------------------------------------ */

// The five processKind values Muninn actually receives -- no discovery API exists for this list,
// see components/process-picker.tsx's own note on why it's hardcoded rather than fetched. WORKER
// (design doc §6) is shipped one level below AGENT: a worker JVM has no host:port of its own, so
// its processId is `{nodeId}:{workerId}` rather than a self-reported address.
export type ProcessKind = "CONTROLPLANE" | "FAFNIR" | "STORE" | "AGENT" | "WORKER";

export interface ProcessTarget {
  processKind: ProcessKind;
  processId: string;
}

/** Envelope returned by GET /metrics-history/* and GET /traces-history/* -- identical shape to
 * GET /logs/* (Page<T> above is a different, cursor-only shape used by every other list screen). */
export interface HistoryEnvelope<T> {
  lines: T[];
  olderCursor: string | null;
  newerCursor: string | null;
}

export interface MetricsHistoryLine {
  timestamp: string;
  name: string;
  type: string;
  tags: Record<string, string>;
  measurements: Record<string, number>;
  /** Only present on some TIMER-typed lines. Keys are string fractions ("0.5", "0.95", "0.99"). */
  percentiles?: Record<string, number>;
}

export interface TraceSpanLine {
  timestamp: string;
  traceId: string;
  spanId: string;
  parentSpanId: string;
  name: string;
  kind: string;
  status: string;
  /** Arbitrary span attributes are flattened directly onto the object by MuninnSpanExporter. */
  [attr: string]: unknown;
}

/* ---------------------------------------------------------------------------
 * Audit trail -- GET /audit
 * ------------------------------------------------------------------------ */

export type AuditVerb = "READ" | "WRITE" | "DELETE";

export interface AuditEvent {
  id: string;
  principal: string;
  groups: string[];
  resourceKind: string;
  verb: AuditVerb;
  tenantId?: string;
  targetId?: string;
  allowed: boolean;
  occurredAtEpochMilli: number;
}

export interface AuditFilter {
  principal?: string;
  resource?: string;
  tenant?: string;
  /** ISO-8601 instant. */
  since?: string;
  limit?: number;
}

/* ---------------------------------------------------------------------------
 * Autoscale policy -- optional field on DeploymentSpec/DeploymentSpecInput above
 * ------------------------------------------------------------------------ */

export type AutoscaleCombinationMode = "WORST_SIGNAL" | "WEIGHTED";

export interface AutoscalePolicy {
  minReplicas: number;
  maxReplicas: number;
  targetCpuUtilizationPercent: number;
  targetRequestRatePerSecond?: number;
  targetErrorRatePercent?: number;
  targetQueueDepth?: number;
  combinationMode: AutoscaleCombinationMode;
  cpuWeight?: number;
  requestRateWeight?: number;
  errorRateWeight?: number;
  queueDepthWeight?: number;
}
