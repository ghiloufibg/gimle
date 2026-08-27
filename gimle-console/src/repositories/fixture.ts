import type {
  ConcurrencyPolicy,
  ConfigEntry,
  ConfigMap,
  CronJob,
  DaemonSet,
  DaemonSetInstance,
  Deployment,
  DeploymentInstance,
  Job,
  LifecycleState,
  Node,
  SecretMap,
  SecretMapGroupVersion,
  SecretMapKeyResult,
  SecretMapRollbackResult,
  SecretMetadata,
  StatefulSet,
  StatefulSetInstance,
  Tenant,
} from "@/types";

// Seeded PRNG so mock data is stable across renders/SSR hydration.
function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rand = mulberry32(0xc0ffee);
const pick = <T>(arr: T[]) => arr[Math.floor(rand() * arr.length)];
const intBetween = (lo: number, hi: number) => Math.floor(rand() * (hi - lo + 1)) + lo;

const TIERS: Node["capabilities"]["supportedTiers"][number][] = ["TIER_1", "TIER_2", "TIER_3"];

const LIFECYCLE_WEIGHTS: [LifecycleState, number][] = [
  ["ACTIVE", 70],
  ["STARTING", 10],
  ["RESOLVED", 7],
  ["STOPPING", 5],
  ["INSTALLED", 5],
  ["UNINSTALLED", 3],
];

function weightedLifecycle(): LifecycleState {
  const total = LIFECYCLE_WEIGHTS.reduce((s, [, w]) => s + w, 0);
  let r = rand() * total;
  for (const [state, w] of LIFECYCLE_WEIGHTS) {
    r -= w;
    if (r <= 0) return state;
  }
  return "ACTIVE";
}

// ---------- Tenants ----------
export const tenants: Tenant[] = [
  "acme",
  "globex",
  "initech",
  "umbrella",
  "hooli",
  "stark-industries",
  "wayne-enterprises",
].map((id) => ({
  id,
  quota: {
    maxMemoryBytes: intBetween(8, 64) * 1024 ** 3,
    maxCpuMillicores: intBetween(4, 32) * 1000,
    maxInstances: intBetween(10, 40),
  },
}));

// ---------- Nodes ----------
export const nodes: Node[] = Array.from({ length: 10 }, (_, i) => {
  const totalMem = intBetween(16, 128) * 1024 ** 3;
  const totalCpu = intBetween(8, 32) * 1000;
  const stale = rand() < 0.15;
  const now = Date.now();
  return {
    nodeId: `node-${String(i + 1).padStart(2, "0")}.gimle.cluster`,
    capabilities: {
      supportedTiers:
        rand() < 0.5
          ? ["TIER_1", "TIER_2"]
          : rand() < 0.5
            ? ["TIER_2", "TIER_3"]
            : [TIERS[intBetween(0, 2)]],
    },
    lastHeartbeatAt:
      rand() < 0.05
        ? null
        : new Date(
            now - (stale ? intBetween(35, 300) * 1000 : intBetween(0, 20) * 1000),
          ).toISOString(),
    capacity: {
      totalMemoryBytes: totalMem,
      assignedMemoryBytes: Math.floor(totalMem * (0.2 + rand() * 0.7)),
      totalCpuMillicores: totalCpu,
      assignedCpuMillicores: Math.floor(totalCpu * (0.2 + rand() * 0.7)),
    },
  };
});

// ---------- Deployments ----------
const MODULE_NAMES = [
  "checkout-service",
  "order-handler",
  "billing-gateway",
  "audit-log",
  "notification-router",
  "session-cache",
  "risk-scoring",
  "search-indexer",
  "inventory-sync",
  "feature-flags",
  "webhook-dispatcher",
  "auth-broker",
  "report-generator",
  "media-transcoder",
  "quota-enforcer",
];

// A worker JVM's own id, exactly as reported in its Hello handshake -- only assigned once an
// instance is actually alive in a worker (never for one still INSTALLED/UNINSTALLED, which has no
// worker JVM yet or anymore), matching AgentMain#observationJson's own "present only once known"
// convention.
function fixtureWorkerId(alive: boolean): string | null {
  return alive ? `worker-${intBetween(1000, 9999)}` : null;
}

function makeInstance(idx: number, nodeId: string): DeploymentInstance {
  const state = weightedLifecycle();
  const active = state === "ACTIVE";
  const ready = active && rand() > 0.08;
  const alive = state !== "UNINSTALLED" && state !== "INSTALLED";
  return {
    instanceIndex: idx,
    nodeId,
    observation: {
      lifecycleState: state,
      alive,
      ready,
      requestRatePerSecond: active ? +(rand() * 200).toFixed(1) : 0,
      queueDepth: active ? intBetween(0, 25) : 0,
      cpuMillicoresUsed: active ? intBetween(50, 1500) : intBetween(0, 30),
      memoryBytesUsed: intBetween(64, 1024) * 1024 * 1024,
      workerId: fixtureWorkerId(alive),
    },
  };
}

export const deployments: Deployment[] = Array.from({ length: 42 }, (_, i) => {
  const mod = pick(MODULE_NAMES);
  const version = `${intBetween(0, 3)}.${intBetween(0, 12)}.${intBetween(0, 20)}`;
  const name = `${mod}-${version.replace(/\./g, "-")}-${i}`;
  const replicas = intBetween(1, 6);
  const placed = Math.max(0, replicas - (rand() < 0.15 ? intBetween(1, replicas) : 0));
  const instances = Array.from({ length: placed }, (_, ix) => makeInstance(ix, pick(nodes).nodeId));
  const tenantId = rand() < 0.15 ? null : pick(tenants).id;
  return {
    spec: {
      name,
      moduleId: { name: mod, version },
      artifactPath: `s3://gimle-artifacts/${mod}/${version}/${mod}-${version}.jar`,
      replicas,
      tenantId,
    },
    instances,
    unplacedCount: replicas - placed,
    quotaViolating: rand() < 0.12,
    ...limitRangeViolationFixture(),
  };
});

/** A single draw decides both fields together, so they can never disagree. */
function limitRangeViolationFixture(): Pick<
  Deployment,
  "limitRangeViolating" | "limitRangeViolationReason"
> {
  const violating = rand() < 0.08;
  return {
    limitRangeViolating: violating,
    limitRangeViolationReason: violating ? "request memory 512Mi above maximum 256Mi" : undefined,
  };
}

// ---------- Jobs ----------
const JOB_PHASE_WEIGHTS: [Job["phase"], number][] = [
  ["RUNNING", 50],
  ["SUCCEEDED", 35],
  ["FAILED", 15],
];

function weightedJobPhase(): Job["phase"] {
  const total = JOB_PHASE_WEIGHTS.reduce((s, [, w]) => s + w, 0);
  let r = rand() * total;
  for (const [phase, w] of JOB_PHASE_WEIGHTS) {
    r -= w;
    if (r <= 0) return phase;
  }
  return "RUNNING";
}

export const jobs: Job[] = Array.from({ length: 12 }, (_, i) => {
  const mod = pick(MODULE_NAMES);
  const version = `${intBetween(0, 3)}.${intBetween(0, 12)}.${intBetween(0, 20)}`;
  const name = `${mod}-job-${i}`;
  const phase = weightedJobPhase();
  const attempt = intBetween(0, 2);
  const tenantId = rand() < 0.3 ? pick(tenants).id : null;
  const currentRun =
    phase === "RUNNING"
      ? {
          attempt,
          nodeId: pick(nodes).nodeId,
          observation: {
            lifecycleState: rand() < 0.7 ? ("ACTIVE" as const) : ("STARTING" as const),
            alive: true,
            ready: false,
            requestRatePerSecond: 0,
            queueDepth: 0,
            cpuMillicoresUsed: intBetween(50, 800),
            memoryBytesUsed: intBetween(64, 512) * 1024 * 1024,
            workerId: fixtureWorkerId(true),
          },
        }
      : null;
  return {
    spec: {
      name,
      moduleId: { name: mod, version },
      artifactPath: `s3://gimle-artifacts/${mod}/${version}/${mod}-${version}.jar`,
      backoffLimit: intBetween(1, 6),
      activeDeadlineSeconds: rand() < 0.4 ? intBetween(60, 3600) : undefined,
      tenantId,
    },
    phase,
    currentRun,
  };
});

// ---------- CronJobs ----------
const SCHEDULES = ["0 2 * * *", "*/15 * * * *", "0 0 1 * *", "0 6,18 * * *"];
const CONCURRENCY_POLICIES: ConcurrencyPolicy[] = ["ALLOW", "FORBID", "REPLACE"];

export const cronJobs: CronJob[] = Array.from({ length: 6 }, (_, i) => {
  const mod = pick(MODULE_NAMES);
  const version = `${intBetween(0, 3)}.${intBetween(0, 12)}.${intBetween(0, 20)}`;
  const name = `${mod}-cron-${i}`;
  const tenantId = rand() < 0.3 ? pick(tenants).id : null;
  const firedBefore = rand() < 0.7;
  return {
    spec: {
      name,
      schedule: pick(SCHEDULES),
      jobTemplate: {
        moduleId: { name: mod, version },
        artifactPath: `s3://gimle-artifacts/${mod}/${version}/${mod}-${version}.jar`,
        backoffLimit: intBetween(1, 6),
        activeDeadlineSeconds: rand() < 0.4 ? intBetween(60, 3600) : undefined,
      },
      startingDeadlineSeconds: rand() < 0.3 ? intBetween(60, 600) : undefined,
      concurrencyPolicy: pick(CONCURRENCY_POLICIES),
      tenantId,
    },
    lastScheduleTime: firedBefore
      ? new Date(Date.UTC(2026, 0, 1, intBetween(0, 23), intBetween(0, 59))).toISOString()
      : null,
  };
});

// ---------- DaemonSets ----------
const NODE_LABEL_SETS: string[][] = [[], [], [], ["gpu"], ["ssd"], ["edge"], ["gpu", "ssd"]];

function makeDaemonSetInstance(nodeId: string): DaemonSetInstance {
  const state = weightedLifecycle();
  const active = state === "ACTIVE";
  const ready = active && rand() > 0.08;
  const alive = state !== "UNINSTALLED" && state !== "INSTALLED";
  return {
    nodeId,
    observation: {
      lifecycleState: state,
      alive,
      ready,
      requestRatePerSecond: active ? +(rand() * 50).toFixed(1) : 0,
      queueDepth: active ? intBetween(0, 5) : 0,
      cpuMillicoresUsed: active ? intBetween(20, 400) : intBetween(0, 20),
      memoryBytesUsed: intBetween(32, 256) * 1024 * 1024,
      workerId: fixtureWorkerId(alive),
    },
  };
}

export const daemonSets: DaemonSet[] = Array.from({ length: 4 }, (_, i) => {
  const mod = pick(MODULE_NAMES);
  const version = `${intBetween(0, 3)}.${intBetween(0, 12)}.${intBetween(0, 20)}`;
  const name = `${mod}-agent-${i}`;
  const tenantId = rand() < 0.2 ? pick(tenants).id : null;
  const requiredNodeLabels = pick(NODE_LABEL_SETS);
  const eligibleNodes =
    requiredNodeLabels.length > 0
      ? nodes.filter(() => rand() < 0.6)
      : nodes.filter(() => rand() < 0.9);
  const instances = eligibleNodes.map((n) => makeDaemonSetInstance(n.nodeId));
  return {
    spec: {
      name,
      moduleId: { name: mod, version },
      artifactPath: `s3://gimle-artifacts/${mod}/${version}/${mod}-${version}.jar`,
      placement: { requiredNodeLabels },
      tenantId,
    },
    instances,
  };
});

// ---------- StatefulSets ----------
function makeStatefulSetInstance(idx: number, nodeId: string): StatefulSetInstance {
  const state = weightedLifecycle();
  const active = state === "ACTIVE";
  const ready = active && rand() > 0.08;
  const alive = state !== "UNINSTALLED" && state !== "INSTALLED";
  return {
    instanceIndex: idx,
    nodeId,
    observation: {
      lifecycleState: state,
      alive,
      ready,
      requestRatePerSecond: active ? +(rand() * 100).toFixed(1) : 0,
      queueDepth: active ? intBetween(0, 15) : 0,
      cpuMillicoresUsed: active ? intBetween(50, 800) : intBetween(0, 30),
      memoryBytesUsed: intBetween(128, 2048) * 1024 * 1024,
      workerId: fixtureWorkerId(alive),
    },
  };
}

export const statefulSets: StatefulSet[] = Array.from({ length: 5 }, (_, i) => {
  const mod = pick(MODULE_NAMES);
  const version = `${intBetween(0, 3)}.${intBetween(0, 12)}.${intBetween(0, 20)}`;
  const name = `${mod}-statefulset-${i}`;
  const replicas = intBetween(1, 3);
  const placed = Math.max(0, replicas - (rand() < 0.15 ? 1 : 0));
  const instances = Array.from({ length: placed }, (_, ix) =>
    makeStatefulSetInstance(ix, pick(nodes).nodeId),
  );
  const tenantId = rand() < 0.2 ? pick(tenants).id : null;
  return {
    spec: {
      name,
      moduleId: { name: mod, version },
      artifactPath: `s3://gimle-artifacts/${mod}/${version}/${mod}-${version}.jar`,
      replicas,
      tenantId,
    },
    instances,
    unplacedCount: replicas - placed,
  };
});

// ---------- Config ----------
const CONFIG_KEYS = [
  "db.url",
  "db.username",
  "db.password",
  "cache.ttl.seconds",
  "http.timeout.ms",
  "feature.newCheckout",
  "s3.bucket",
  "s3.secretKey",
  "smtp.host",
  "smtp.password",
  "jwt.signingKey",
  "log.level",
];

export const configByTenant: Record<string, ConfigEntry[]> = Object.fromEntries(
  tenants.map((t) => [
    t.id,
    CONFIG_KEYS.slice(0, intBetween(5, CONFIG_KEYS.length)).map((key) => {
      const encrypted = /password|secret|signingKey/i.test(key);
      return {
        tenantId: t.id,
        key,
        value: encrypted
          ? `enc::${Math.random().toString(36).slice(2, 18)}`
          : key.includes("url")
            ? `postgresql://db.${t.id}.internal:5432/${t.id}`
            : key.includes("bucket")
              ? `gimle-${t.id}-prod`
              : key.includes("host")
                ? `smtp.${t.id}.example.com`
                : key.includes("ttl")
                  ? String(intBetween(30, 3600))
                  : key.includes("timeout")
                    ? String(intBetween(500, 10000))
                    : key.includes("feature")
                      ? String(rand() < 0.5)
                      : key.includes("level")
                        ? pick(["INFO", "DEBUG", "WARN"])
                        : "value",
        encrypted,
      };
    }),
  ]),
);

// Mutations happen in-place so all consumers see the same data.
export function addDeployment(d: Deployment) {
  deployments.unshift(d);
}

export function removeDeployment(name: string) {
  const idx = deployments.findIndex((d) => d.spec.name === name);
  if (idx >= 0) deployments.splice(idx, 1);
}

export function addJob(j: Job) {
  jobs.unshift(j);
}

export function removeJob(name: string) {
  const idx = jobs.findIndex((j) => j.spec.name === name);
  if (idx >= 0) jobs.splice(idx, 1);
}

export function addCronJob(c: CronJob) {
  cronJobs.unshift(c);
}

export function removeCronJob(name: string) {
  const idx = cronJobs.findIndex((c) => c.spec.name === name);
  if (idx >= 0) cronJobs.splice(idx, 1);
}

export function addDaemonSet(d: DaemonSet) {
  daemonSets.unshift(d);
}

export function removeDaemonSet(name: string) {
  const idx = daemonSets.findIndex((d) => d.spec.name === name);
  if (idx >= 0) daemonSets.splice(idx, 1);
}

export function addStatefulSet(s: StatefulSet) {
  statefulSets.unshift(s);
}

export function removeStatefulSet(name: string) {
  const idx = statefulSets.findIndex((s) => s.spec.name === name);
  if (idx >= 0) statefulSets.splice(idx, 1);
}

export function updateTenant(id: string, quota: Tenant["quota"]) {
  const t = tenants.find((x) => x.id === id);
  if (t) t.quota = quota;
}

export function removeTenant(id: string) {
  const i = tenants.findIndex((x) => x.id === id);
  if (i >= 0) tenants.splice(i, 1);
  delete configByTenant[id];
  delete secretsByTenant[id];
  delete configMapsByTenant[id];
  delete secretMapsByTenant[id];
  for (const key of Object.keys(secretMapGroupVersionsByTenant)) {
    if (key.startsWith(`${id}::`)) delete secretMapGroupVersionsByTenant[key];
  }
}

export function upsertConfig(entry: ConfigEntry) {
  const list = (configByTenant[entry.tenantId] ??= []);
  const i = list.findIndex((e) => e.key === entry.key);
  if (i >= 0) list[i] = entry;
  else list.push(entry);
}

export function removeConfig(tenantId: string, key: string) {
  const list = configByTenant[tenantId];
  if (!list) return;
  const i = list.findIndex((e) => e.key === key);
  if (i >= 0) list.splice(i, 1);
}

// ---- Secrets ----

const SECRET_KEYS = ["db.password", "api.key", "jwt.signingKey", "s3.secretKey", "smtp.password"];

/** Mock-only internal shape -- every claimed version's plaintext, index 0 = version 1, never
 * dropped even after a soft delete (mirrors Fafnir's own "@N entries stay on disk" semantics). */
interface MockSecret {
  tenantId: string;
  key: string;
  versions: string[];
  deleted: boolean;
}

export const secretsByTenant: Record<string, MockSecret[]> = Object.fromEntries(
  tenants.map((t) => [
    t.id,
    SECRET_KEYS.slice(0, intBetween(2, SECRET_KEYS.length)).map((key) => ({
      tenantId: t.id,
      key,
      versions: [`s3cr3t-${Math.random().toString(36).slice(2, 10)}`],
      deleted: false,
    })),
  ]),
);

export function secretMetadata(secret: MockSecret): SecretMetadata {
  return {
    tenantId: secret.tenantId,
    key: secret.key,
    latestVersion: secret.versions.length,
    deleted: secret.deleted,
  };
}

/** Upserts by appending a new version -- a write always claims a new version, never overwrites an
 * existing one -- so a previously soft-deleted key becomes live again. */
export function upsertSecret(tenantId: string, key: string, value: string): MockSecret {
  const list = (secretsByTenant[tenantId] ??= []);
  const existing = list.find((s) => s.key === key);
  if (existing) {
    existing.versions.push(value);
    existing.deleted = false;
    return existing;
  }
  const created: MockSecret = { tenantId, key, versions: [value], deleted: false };
  list.push(created);
  return created;
}

/** {@code destroy}: hard-delete (removes the entry entirely); otherwise soft-delete, keeping every
 * version reachable by explicit version number. */
export function removeSecret(tenantId: string, key: string, destroy: boolean) {
  const list = secretsByTenant[tenantId];
  if (!list) return;
  const i = list.findIndex((s) => s.key === key);
  if (i < 0) return;
  if (destroy) list.splice(i, 1);
  else list[i].deleted = true;
}

// ---- ConfigMaps ----

export const configMapsByTenant: Record<string, ConfigMap[]> = Object.fromEntries(
  tenants.map((t) => [
    t.id,
    [
      {
        tenantId: t.id,
        name: "app-config",
        version: 1,
        data: { "log.level": "INFO", "feature.newCheckout": "true" },
      },
    ],
  ]),
);

export function findConfigMap(tenantId: string, name: string): ConfigMap | undefined {
  return (configMapsByTenant[tenantId] ?? []).find((c) => c.name === name);
}

/** Full replace, optimistic-concurrency guarded the same way the real API is: a present {@code
 * expectedVersion} that doesn't match the current one is rejected rather than silently applied. */
export function upsertConfigMap(
  tenantId: string,
  name: string,
  data: Record<string, string>,
  expectedVersion?: number,
): ConfigMap {
  const list = (configMapsByTenant[tenantId] ??= []);
  const existing = list.find((c) => c.name === name);
  const currentVersion = existing?.version ?? 0;
  if (expectedVersion !== undefined && expectedVersion !== currentVersion) {
    throw new ConfigMapConflict(currentVersion, existing?.data ?? {});
  }
  const saved: ConfigMap = { tenantId, name, version: currentVersion + 1, data };
  if (existing) Object.assign(existing, saved);
  else list.push(saved);
  return saved;
}

export function removeConfigMap(tenantId: string, name: string) {
  const list = configMapsByTenant[tenantId];
  if (!list) return;
  const i = list.findIndex((c) => c.name === name);
  if (i >= 0) list.splice(i, 1);
}

/** Mirrors the real API's 409 body shape ({@code {currentVersion, currentData}}) so
 * {@link MockConfigMapsRepository} and {@link HttpConfigMapsRepository} surface the identical
 * error shape to the store regardless of which backs the console. */
export class ConfigMapConflict extends Error {
  constructor(
    readonly currentVersion: number,
    readonly currentData: Record<string, string>,
  ) {
    super(`configmap changed since it was loaded (now version ${currentVersion})`);
  }
}

// ---- SecretMaps ----
//
// Unlike ConfigMap, there is no single object-level version here -- each key keeps its own
// independent version ledger, so the mock shape stores each key's own version/deleted state
// directly rather than one object-level version counter.

export const secretMapsByTenant: Record<string, SecretMap[]> = Object.fromEntries(
  tenants.map((t) => [
    t.id,
    [
      {
        tenantId: t.id,
        name: "db-creds",
        keys: [
          { key: "username", latestVersion: 1, deleted: false },
          { key: "password", latestVersion: 1, deleted: false },
        ],
      },
    ],
  ]),
);

export function findSecretMap(tenantId: string, name: string): SecretMap | undefined {
  return (secretMapsByTenant[tenantId] ?? []).find((s) => s.name === name);
}

/** Bulk-sets every key in {@code data} under one SecretMap name, in order -- each key succeeds or
 * fails independently, mirroring SecretMapStore.setMany's own per-key reporting rather than an
 * all-or-nothing write. Mock-only: never actually fails a key, since there's no real contention to
 * simulate here. */
export function upsertSecretMap(
  tenantId: string,
  name: string,
  data: Record<string, string>,
): SecretMapKeyResult[] {
  const list = (secretMapsByTenant[tenantId] ??= []);
  let secretMap = list.find((s) => s.name === name);
  if (!secretMap) {
    secretMap = { tenantId, name, keys: [] };
    list.push(secretMap);
  }
  const mutableSecretMap = secretMap;
  const results = Object.keys(data).map((key) => {
    const existing = mutableSecretMap.keys.find((k) => k.key === key);
    if (existing) {
      existing.latestVersion += 1;
      existing.deleted = false;
    } else {
      mutableSecretMap.keys.push({ key, latestVersion: 1, deleted: false });
    }
    return { key, version: existing ? existing.latestVersion : 1 };
  });
  stampSecretMapGroupVersion(tenantId, name, mutableSecretMap);
  return results;
}

export function removeSecretMap(tenantId: string, name: string) {
  const list = secretMapsByTenant[tenantId];
  if (!list) return;
  const i = list.findIndex((s) => s.name === name);
  if (i >= 0) list.splice(i, 1);
}

// Group versions -- one ledger entry per (tenantId, name) recording the full member state at the
// moment it was stamped, mirroring com.gimle.fafnir.secretmap.SecretMapStore's own group-version
// ledger. Keyed on a plain "tenantId::name" string rather than a nested map: this fixture already
// uses flat Record<string, T[]> maps everywhere else, and a SecretMap name never contains "::".
export const secretMapGroupVersionsByTenant: Record<string, SecretMapGroupVersion[]> = {};

function groupVersionsKey(tenantId: string, name: string): string {
  return `${tenantId}::${name}`;
}

function stampSecretMapGroupVersion(
  tenantId: string,
  name: string,
  secretMap: SecretMap,
  rollbackOfGroupVersion?: number,
): number {
  const list = (secretMapGroupVersionsByTenant[groupVersionsKey(tenantId, name)] ??= []);
  const next = list.length > 0 ? list[list.length - 1].groupVersion + 1 : 1;
  const entry: SecretMapGroupVersion = {
    groupVersion: next,
    keys: secretMap.keys.map((k) => ({ ...k })),
  };
  if (rollbackOfGroupVersion !== undefined) entry.rollbackOfGroupVersion = rollbackOfGroupVersion;
  list.push(entry);
  return next;
}

export function listSecretMapGroupVersions(
  tenantId: string,
  name: string,
): SecretMapGroupVersion[] {
  return secretMapGroupVersionsByTenant[groupVersionsKey(tenantId, name)] ?? [];
}

/** Restores every key {@code targetGroupVersion} recorded, mirroring
 * SecretMapStore#rollback: a live key's content is "rewritten" as a new version (mock has no real
 * content to restore, only the version counter), a deleted key is re-marked deleted, and the
 * rollback itself is stamped as a brand-new forward-only group version. */
export function rollbackSecretMap(
  tenantId: string,
  name: string,
  groupVersion: number,
): SecretMapRollbackResult {
  const versions = secretMapGroupVersionsByTenant[groupVersionsKey(tenantId, name)] ?? [];
  const target = versions.find((v) => v.groupVersion === groupVersion);
  if (!target) throw new Error(`no such group version of ${name}: ${groupVersion}`);

  const list = (secretMapsByTenant[tenantId] ??= []);
  let secretMap = list.find((s) => s.name === name);
  if (!secretMap) {
    secretMap = { tenantId, name, keys: [] };
    list.push(secretMap);
  }
  const mutableSecretMap = secretMap;
  const results: SecretMapKeyResult[] = target.keys.map((snapshot) => {
    let current = mutableSecretMap.keys.find((k) => k.key === snapshot.key);
    if (!current) {
      current = { key: snapshot.key, latestVersion: 0, deleted: false };
      mutableSecretMap.keys.push(current);
    }
    if (snapshot.deleted) {
      current.deleted = true;
    } else {
      current.latestVersion += 1;
      current.deleted = false;
    }
    return { key: snapshot.key, version: current.latestVersion };
  });
  const newGroupVersion = stampSecretMapGroupVersion(
    tenantId,
    name,
    mutableSecretMap,
    groupVersion,
  );
  return { results, groupVersion: newGroupVersion };
}
