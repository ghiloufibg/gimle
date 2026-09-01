export type LifecycleState =
  | "INSTALLED"
  | "RESOLVED"
  | "STARTING"
  | "ACTIVE"
  | "STOPPING"
  | "UNINSTALLED"
  | "FAILED"
  // Run-to-completion success terminal -- only a Job-kind instance's own observation ever
  // reports this; a Deployment replica's lifecycleState never does.
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
  errorRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
  // The raw id ("worker-1234") the worker JVM hosting this instance reported in its own Hello
  // handshake with the agent -- null until that handshake completes, and always null for a plain
  // Vessel instance. Combined with the instance's own nodeId (tracked alongside this observation,
  // not duplicated into it) as `${nodeId}:${workerId}`, this is the exact processId shape
  // components/process-picker.tsx already expects for a WORKER process target.
  workerId: string | null;
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
  disruption?: DisruptionBudget;
}

export interface DeploymentSpecInput {
  name: string;
  moduleId: ModuleId;
  artifactPath: string;
  replicas: number;
  tenantId: string | null;
  autoscale?: AutoscalePolicy;
  disruption?: DisruptionBudget;
}

export interface Deployment {
  spec: DeploymentSpec;
  instances: DeploymentInstance[];
  unplacedCount: number;
  quotaViolating: boolean;
  limitRangeViolating: boolean;
  /** Set only when limitRangeViolating -- which bound (min/max, request/limit) is failing. */
  limitRangeViolationReason?: string;
}

/* ---------------------------------------------------------------------------
 * Jobs -- run-to-completion, not run-forever: no `replicas`/
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
 * CronJobs -- a thin schedule generator over Job: no `phase`/
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
 * DaemonSets -- one instance per eligible node, not an
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
 * StatefulSets -- the last workload-diversity kind: stable per-index
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
  /** Cordoned/tainted only ever affect future scheduling -- neither evicts an already-running
   * instance. `taints` is the sorted set of tenant ids this node currently refuses new placements
   * for. */
  cordoned: boolean;
  taints: string[];
}

export interface Tenant {
  id: string;
  quota: {
    maxMemoryBytes: number;
    maxCpuMillicores: number;
    maxInstances: number;
  };
  /** Server-computed, real assigned usage (resourceRequest × committedInstances summed across
   * every Deployment/Job/DaemonSet/StatefulSet sharing the tenant) -- the same numbers admission
   * and QuotaReconciler actually enforce against, not a client-side approximation. */
  usage: {
    memoryBytes: number;
    cpuMillicores: number;
    instances: number;
  };
  quotaViolating: boolean;
}

/**
 * The ClusterIP analogue: a stable name fronting one or more Deployments/DaemonSets/StatefulSets
 * matched by name. `tenantId` is absent (not empty-string) for an untenanted Service, matching how
 * `ApiServer#serviceToJson` only writes the field when the spec actually carries one.
 */
export interface Service {
  name: string;
  tenantId?: string;
  deploymentNames: string[];
  port: number;
  targetPort: number;
}

/** `GET /services/{name}/endpoints` -- live, reconciler-independent, never cached alongside `Service`. */
export interface ServiceEndpoints {
  name: string;
  port: number;
  targetPort: number;
  endpoints: { host: string; port: number }[];
}

/**
 * A declared, deny-by-default restriction on which other tenants may call into `tenantId`'s own
 * Services. `deploymentNames` empty means the whole tenant is covered, matching
 * `NetworkPolicySpec#deploymentNames()`'s `Optional.empty()` serializing as `[]` over the wire.
 */
export interface NetworkPolicy {
  name: string;
  tenantId: string;
  deploymentNames: string[];
  allowedCallerTenantIds: string[];
}

export interface ConfigEntry {
  tenantId: string;
  key: string;
  value: string;
  encrypted: boolean;
}

// Fafnir's /secrets/* surface never returns a value alongside metadata --
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

// A named, multi-key bundle a deployment attaches by `configMapRefs` instead of receiving its
// tenant's entire flat Config set. Unlike Config's per-key rows, `data` here is the whole object's
// current content -- `version` exists purely for the optimistic-concurrency conflict check on save.
export interface ConfigMap {
  tenantId: string;
  name: string;
  version: number;
  data: Record<string, string>;
}

// The secrets analogue of ConfigMap: a named grouping over Fafnir's existing per-key versioned
// secrets, attached by `secretMapRefs`. Unlike ConfigMap, there is no single object-level
// `version` -- each key keeps its own independent version ledger (see
// com.gimle.fafnir.secretmap.SecretMapStore), so `keys` below is a list of per-key metadata, the
// same shape SecretMetadata already uses, never a value alongside it.
export interface SecretMapKeyMetadata {
  key: string;
  latestVersion: number;
  deleted: boolean;
}

export interface SecretMap {
  tenantId: string;
  name: string;
  keys: SecretMapKeyMetadata[];
}

/** One key's outcome from a SecretMap bulk `set` -- exactly one of `version`/`error` is present,
 * mirroring com.gimle.fafnir.secretmap.SecretMapStore.SecretMapKeyResult. */
export interface SecretMapKeyResult {
  key: string;
  version?: number;
  error?: string;
}

/** One stamped group version of a SecretMap -- mirroring
 * com.gimle.fafnir.secretmap.SecretMapStore.SecretMapGroupVersion. `rollbackOfGroupVersion` is
 * present only when this group version was itself produced by a rollback. */
export interface SecretMapGroupVersion {
  groupVersion: number;
  keys: SecretMapKeyMetadata[];
  rollbackOfGroupVersion?: number;
}

/** The response of a rollback -- per-key results plus the brand-new group version it was
 * recorded as (rollback never rewrites the target group version or anything after it). */
export interface SecretMapRollbackResult {
  results: SecretMapKeyResult[];
  groupVersion: number;
}

/** One revision of a Deployment/DaemonSet/StatefulSet's ControllerRevision history -- mirrors
 * ApiServer.java's controllerRevisionToJson(). Newest-first when returned by fetchRevisions.
 * `rollbackOfRevision` is present only when this revision was itself produced by a rollback. */
export interface ControllerRevision {
  revision: number;
  createdAtEpochMilli: number;
  rollbackOfRevision?: number;
  moduleId: ModuleId;
  artifactPath: string;
  artifactSha256?: string;
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
  errorRatePerSecond: number;
  queueDepth: number;
  cpuMillicoresUsed: number;
  memoryBytesUsed: number;
  workerId: string | null;
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

/**
 * The server-side content filter every /logs/* route accepts, alongside its timestamp cursor.
 * `minLevel` is a threshold (WARN means WARN and above), `contains` a plain case-insensitive
 * substring (never a regex); `null` on either means "no constraint".
 */
export interface LogFilter {
  minLevel: LogLevel | null;
  contains: string | null;
}

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
  // True only for the synthetic plaintext-mode free-pass /auth/session hands back when nobody's
  // actually logged in (see ApiServer#handleAuthSession) -- lets the login page tell "there's
  // nothing to redirect for" apart from "an operator is actually signed in".
  anonymous?: boolean;
}

/* ---------------------------------------------------------------------------
 * Process-scoped history (metrics + traces) -- GET /metrics-history/*, GET /traces-history/*
 * ------------------------------------------------------------------------ */

// The five processKind values Muninn actually receives -- no discovery API exists for this list,
// see components/process-picker.tsx's own note on why it's hardcoded rather than fetched. WORKER
// is shipped one level below AGENT: a worker JVM has no host:port of its own, so its processId is
// `{nodeId}:{workerId}` rather than a self-reported address.
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

export type AuditVerb = "READ" | "WRITE" | "DELETE" | "APPROVE";

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

/**
 * The trail's own retention state, independent of whatever filter/limit a given query applied --
 * lets the console tell "this is the complete record" from "this cluster crossed the retention
 * cap; earlier decisions are gone" without cross-referencing a control-plane log line.
 */
export interface AuditTrailStatus {
  retainedCount: number;
  evictedTotal: number;
  oldestRetainedAtEpochMilli?: number;
  truncated: boolean;
}

/** {@code GET /audit}'s actual wire shape -- {@link AuditTrailStatus}'s fields sit flat alongside
 * {@code events}, not nested, matching {@code ApiServer#handleAudit}'s own response body. */
export type AuditQueryResult = AuditTrailStatus & { events: AuditEvent[] };

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

/* ---------------------------------------------------------------------------
 * Disruption budget -- optional field on DeploymentSpec/DeploymentSpecInput above
 * ------------------------------------------------------------------------ */

export interface DisruptionBudget {
  maxUnavailable: number;
  maxSurge: number;
}

/* ---------------------------------------------------------------------------
 * RBAC (Access Control): roles, role bindings, accounts
 * ------------------------------------------------------------------------ */

export type ResourceKind =
  | "DEPLOYMENT"
  | "JOB"
  | "DAEMONSET"
  | "STATEFULSET"
  | "NODE"
  | "TENANT"
  | "CONFIG"
  | "SECRET"
  | "LOGS"
  | "CERTIFICATE_REQUEST"
  | "BOOTSTRAP_TOKEN"
  | "ROLE"
  | "ROLE_BINDING"
  | "ACCOUNT"
  | "AUDIT"
  | "ARTIFACT"
  | "SERVICE"
  | "NETWORK_POLICY";

export type Verb = "READ" | "WRITE" | "DELETE" | "APPROVE";

export interface Permission {
  resource: ResourceKind;
  verb: Verb;
  tenantScope?: string;
}

export interface Role {
  name: string;
  permissions: Permission[];
}

export interface RoleBinding {
  id: string;
  subject: string;
  roleName: string;
}

/** List/detail shape only — the API never returns password material. */
export interface Account {
  username: string;
  groups: string[];
}

export const RESOURCE_KINDS: ResourceKind[] = [
  "DEPLOYMENT",
  "JOB",
  "DAEMONSET",
  "STATEFULSET",
  "NODE",
  "TENANT",
  "CONFIG",
  "SECRET",
  "LOGS",
  "CERTIFICATE_REQUEST",
  "BOOTSTRAP_TOKEN",
  "ROLE",
  "ROLE_BINDING",
  "ACCOUNT",
  "AUDIT",
  "ARTIFACT",
  "SERVICE",
  "NETWORK_POLICY",
];

export const VERBS: Verb[] = ["READ", "WRITE", "DELETE", "APPROVE"];

/* ---------------------------------------------------------------------------
 * Artifact registry (Andvari)
 * ------------------------------------------------------------------------ */

/**
 * One immutable stored jar at a {@code moduleId}/{version} coordinate. The registry is
 * content-addressed, so `sha256` identifies the bytes themselves -- a coordinate's contents can
 * never change under it, which is why nothing here carries a "last modified" field.
 */
export interface ArtifactVersion {
  moduleId: string;
  version: string;
  sha256: string;
  sizeBytes: number;
  pushedAtEpochMilli: number;
  pushedBy: string;
  /** "JAR" (a single module/vessel jar) or "BUNDLE" (a zipped multi-file application). */
  kind?: "JAR" | "BUNDLE";
}

/* ---------------------------------------------------------------------------
 * Custom kinds (Galdr)
 * ------------------------------------------------------------------------ */

/**
 * One field of a KindDefinition's declared schema, as `/kinddefinitions` renders it. The shape is
 * recursive (`items` for lists, `fields` for objects) and deliberately loose beyond the common
 * attributes -- the console renders the schema, it never re-validates against it.
 */
export interface KindSchemaField {
  name: string;
  type: "string" | "int" | "double" | "bool" | "enum" | "list" | "object";
  required?: boolean;
  default?: unknown;
  values?: string[];
  min?: number;
  max?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  items?: KindSchemaField;
  fields?: KindSchemaField[];
}

/** A dotted path into an instance's spec/status, rendered as an extra table column. */
export interface KindPrintColumn {
  name: string;
  path: string;
}

/** One stored KindDefinition -- the schema teaching the cluster a new kind. */
export interface KindDefinitionSummary {
  kindName: string;
  scope: "Tenant" | "Cluster";
  description: string;
  names: { plural?: string; shortNames: string[] };
  schema: { fields: KindSchemaField[] };
  printColumns: KindPrintColumn[];
  generation: number;
}

/**
 * One custom resource with spec and status side by side. `status` is null until an operator first
 * reports one -- the server never fabricates an empty object -- and `tenantId` is absent for an
 * instance of a Cluster-scoped kind.
 */
export interface CustomResourceItem {
  kind: string;
  name: string;
  tenantId?: string;
  generation: number;
  spec: Record<string, unknown>;
  status: Record<string, unknown> | null;
}
