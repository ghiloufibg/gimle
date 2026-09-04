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
  // Both optional on the wire (ApiServer's own observationToJson only writes them once the
  // worker has reported them) -- absent means "not yet known", never "no limit"/"no tier".
  isolationTier?: Tier;
  resourceLimit?: ResourceBound;
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
  /**
   * The scheduler's own words for why a replica is sitting unplaced. Set only once a reconciler
   * tick has actually refused an index -- a deployment admitted moments ago is unplaced without
   * having been refused anything yet.
   */
  unplacedReason?: string;
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
  /** See `Deployment.unplacedReason`. */
  unplacedReason?: string;
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
  /**
   * Absent when the Service declares no target port, in which case each backing instance's own
   * single reported port stands in. A declared one is matched exactly: an instance not reporting
   * it contributes no endpoint.
   */
  targetPort?: number;
}

/**
 * One live instance of a named workload, as `GET /endpoints/{name}` reports it -- keyed by workload
 * name rather than by a declared Service, and carrying every port that instance reported under the
 * `vessel.env` variable name it was declared as. `host` is null until the node it was placed on has
 * registered an API address; `ports` is empty until it has heartbeated an observation.
 */
export interface WorkloadEndpoint {
  instanceIndex: number;
  nodeId: string;
  host: string | null;
  ports: Record<string, number>;
}

/** `GET /services/{name}/endpoints` -- live, reconciler-independent, never cached alongside `Service`. */
export interface ServiceEndpoints {
  name: string;
  port: number;
  targetPort?: number;
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

// The shapes Fafnir validates a secret's plaintext against at write time. "opaque" is the default
// and is never validated; the two PEM types are checked structurally when the value is written, so
// a malformed certificate or key fails at the write rather than at a later module launch.
export type SecretType = "opaque" | "pem-certificate" | "pem-private-key";

export const SECRET_TYPES: SecretType[] = ["opaque", "pem-certificate", "pem-private-key"];

// One stored version of a secret, described: Fafnir records who wrote each version, when, and what
// type it was declared as, so "who wrote version 3, and when" is answerable from the version list
// itself rather than by correlating against the audit trail.
export interface SecretVersion {
  version: number;
  author: string;
  writtenAtEpochMilli: number;
  type: SecretType;
}

export interface SecretValue {
  tenantId: string;
  key: string;
  version: number;
  value: string;
  type: SecretType;
  author: string;
  writtenAtEpochMilli: number;
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
  // Mirrors InstanceObservation's own fields of the same name -- see there for why both are
  // optional.
  isolationTier?: Tier;
  resourceLimit?: ResourceBound;
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

// The processKind values Muninn actually receives -- no discovery API exists for this list, see
// components/process-picker.tsx's own note on why it's hardcoded rather than fetched. WORKER is
// shipped one level below AGENT: a worker JVM has no host:port of its own, so its processId is
// `{nodeId}:{workerId}` rather than a self-reported address. SKALD ships under its own responder's
// `host:dnsPort`, the address that replica answers DNS queries on.
export type ProcessKind = "CONTROLPLANE" | "FAFNIR" | "STORE" | "AGENT" | "WORKER" | "SKALD";

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
  /** ISO-8601 instant; the wire parameter is epoch millis, converted at the repository boundary. */
  since?: string;
  /** Page size. Every page of one query must use the same one for its cursors to stay valid. */
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

/**
 * The paging half of `GET /audit`'s envelope, describing this one query rather than the trail's own
 * retention state. `matchedCount` counts every retained event matching the filters, so a screen can
 * say "showing 100 of 412" instead of only "100 rows"; `nextCursor` is an opaque marker for the
 * page immediately older than the last event returned, absent once there is nothing older left.
 *
 * `cursorExpired` is the ring buffer showing through: the event a cursor anchored on can itself be
 * discarded while an operator pages towards it. Eviction only ever discards from the oldest end, so
 * everything the page would have held went with the anchor -- the page is genuinely empty, and this
 * flag is what separates that from having simply reached the end of the trail.
 */
export interface AuditPageStatus {
  matchedCount: number;
  nextCursor?: string;
  cursorExpired: boolean;
}

/** {@code GET /audit}'s actual wire shape -- {@link AuditTrailStatus}'s and {@link AuditPageStatus}'s
 * fields sit flat alongside {@code events}, not nested, matching {@code ApiServer#handleAudit}'s own
 * response body. */
export type AuditQueryResult = AuditTrailStatus & AuditPageStatus & { events: AuditEvent[] };

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

/**
 * Mirrors `com.gimle.core.authz.ResourceKind`, in that enum's own declaration order. This copy is
 * only the offline fallback and the compile-time type: the Roles permission picker fills itself
 * from `GET /authz/vocabulary`, which serves the running control plane's own enum, so a kind added
 * server-side is grantable here whether or not this list has caught up.
 */
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
  | "NETWORK_POLICY"
  | "CONFIGMAP"
  | "SECRETMAP"
  | "LIMIT_RANGE"
  | "FAULT"
  | "KIND_DEFINITION"
  | "CUSTOM_RESOURCE"
  | "BACKUP"
  | "ALERT_RULE";

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

/** Offline fallback for the permission picker when `GET /authz/vocabulary` cannot be reached. */
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
  "CONFIGMAP",
  "SECRETMAP",
  "LIMIT_RANGE",
  "FAULT",
  "KIND_DEFINITION",
  "CUSTOM_RESOURCE",
  "BACKUP",
  "ALERT_RULE",
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

/* ---------------------------------------------------------------------------
 * LimitRanges -- the per-workload min/max resource bound, keyed by tenant
 * ------------------------------------------------------------------------ */

/**
 * One `{memory, cpu}` bound. Both fields are always present together -- the API rejects a bound
 * carrying only one -- and both are the same human-readable quantity strings a manifest declares
 * (e.g. "512Mi", "500m"), never parsed into numbers on the wire.
 */
export interface ResourceBound {
  memory: string;
  cpu: string;
}

/**
 * A tenant's LimitRange: the min/max any single workload within it may request or limit. Distinct
 * from the tenant's own `quota`, which bounds the tenant in aggregate.
 *
 * Every bound is independently optional -- the API writes a key only when the stored spec actually
 * carries that bound, so an absent key means "unbounded", never zero. `tenantId` is the resource's
 * identity: it comes from the URL path (`/limitranges/{tenantId}`), not the request body, so a
 * write sends only the four bounds.
 */
export interface LimitRange {
  tenantId: string;
  minRequest?: ResourceBound;
  maxRequest?: ResourceBound;
  minLimit?: ResourceBound;
  maxLimit?: ResourceBound;
}

/* ---------------------------------------------------------------------------
 * Volumes -- StatefulSet persistent storage, aggregated across every node
 * ------------------------------------------------------------------------ */

/**
 * One volume directory on one node, as the control plane's `/volumes` aggregation renders it: the
 * owning agent's own inventory entry (`tenantId`/`statefulSet`/`instanceIndex`/`volumeName`/
 * `usedBytes`/`path`/`inUse`) plus the two fields only the control plane can add -- `nodeId`, and
 * `attached`, whether the store's sticky binding still attaches this index to this node.
 *
 * `attached === false` is a retained orphan: a volume the default `Retain` reclaim policy left
 * behind, and the only kind that may be destroyed. `inUse` is the agent's independent, node-local
 * answer to a related question (does a supervised instance hold it right now), which is why both
 * travel rather than one being derived from the other.
 *
 * `tenantId` is explicitly `null` (not absent) for an untenanted volume -- the agent writes the key
 * unconditionally, unlike the absent-when-untenanted convention every other resource here uses.
 */
export interface Volume {
  tenantId: string | null;
  statefulSet: string;
  instanceIndex: number;
  volumeName: string;
  usedBytes: number;
  path: string;
  inUse: boolean;
  nodeId: string;
  attached: boolean;
}

/**
 * `GET /volumes`. A node whose agent is unreachable contributes an `unreachableNodes` entry rather
 * than failing the whole listing, so one dark node never hides every other node's volumes --
 * meaning an empty `volumes` alongside a non-empty `unreachableNodes` is "unknown", not "none".
 * The key is omitted entirely when every node answered.
 */
export interface VolumeListing {
  volumes: Volume[];
  unreachableNodes?: string[];
}

/* ---------------------------------------------------------------------------
 * Sealing keys (Fafnir, proxied) -- the seal-offline asymmetric key lifecycle
 * ------------------------------------------------------------------------ */

/**
 * `GET /seal/public-key`. `publicKey` is the base64 of the key's X.509 SubjectPublicKeyInfo DER --
 * what a caller feeds back to `gimle seal value --public-key` to seal a value entirely offline.
 * The one proxied route with no authorization at all: a caller who can seal but never read needs
 * this key before it can seal anything, so gating it would protect nothing.
 */
export interface SealingPublicKey {
  sealingKeyId: number;
  publicKey: string;
  algorithm: string;
}

/** `POST /seal/rotate-key` -- the newly-minted key id now active for sealing. */
export interface SealingKeyRotation {
  activeSealingKeyId: number;
}

/**
 * `POST /seal/retire-key` -- the key id actually retired, echoed back. Retiring only blocks
 * unwrapping blobs sealed under that key and not yet committed; an already-applied SecretMap value
 * was re-encrypted under Fafnir's own symmetric key at commit time and is unaffected.
 */
export interface SealingKeyRetirement {
  retiredKeyId: number;
}

/* ---------------------------------------------------------------------------
 * Instance lifecycle events
 * ------------------------------------------------------------------------ */

/**
 * One value per entry an instance's own timeline can record: the lifecycle transitions, plus
 * `LIVENESS_FAILED`, which records why the restart around it happened rather than a transition.
 */
export type InstanceEventKind =
  | "INSTALLED"
  | "RESOLVED"
  | "STARTING"
  | "ACTIVE"
  | "STOPPING"
  | "UNINSTALLED"
  | "TRANSITION_FAILED"
  | "COMPLETED"
  | "LIVENESS_FAILED";

/**
 * One durable entry in a single instance's lifecycle timeline -- distinct from the cross-resource
 * `AuditEvent` trail. `causeSummary` is present only on a `TRANSITION_FAILED` event and
 * deliberately holds an exception's class name plus message, never a stack trace.
 *
 * `id` is minted once where the transition occurred and travels unchanged through every hop, so it
 * is a stable identity independent of storage order.
 */
export interface InstanceEvent {
  id: string;
  deploymentName: string;
  instanceIndex: number;
  kind: InstanceEventKind;
  message: string;
  causeSummary?: string;
  occurredAtEpochMilli: number;
}

/* ---------------------------------------------------------------------------
 * Deployment metrics rollup
 * ------------------------------------------------------------------------ */

/**
 * One row of `GET /metrics`: a deployment's live instances averaged into a single pair of rates.
 * `instanceCount` counts only instances whose owning node's heartbeat currently carries an
 * observation for them, so it can sit below the spec's own replica count while placements settle,
 * and both averages are `0` -- not absent -- when it is zero.
 *
 * Deliberately carries no `tenantId`: the rollup is keyed by deployment name alone, so two tenants
 * each running a deployment of the same name produce two rows indistinguishable by name.
 */
export interface DeploymentMetricsRollup {
  /** `null` for a deployment in the untenanted namespace; with the name, a row's full identity. */
  tenantId: string | null;
  deploymentName: string;
  instanceCount: number;
  avgRequestRatePerSecond: number;
  avgErrorRatePerSecond: number;
}
