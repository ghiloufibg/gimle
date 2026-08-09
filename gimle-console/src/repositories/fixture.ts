import type {
  Deployment,
  DeploymentInstance,
  LifecycleState,
  Node,
  Tenant,
  ConfigEntry,
  SecretMetadata,
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

export function updateTenant(id: string, quota: Tenant["quota"]) {
  const t = tenants.find((x) => x.id === id);
  if (t) t.quota = quota;
}

export function removeTenant(id: string) {
  const i = tenants.findIndex((x) => x.id === id);
  if (i >= 0) tenants.splice(i, 1);
  delete configByTenant[id];
  delete secretsByTenant[id];
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

// ---- Secrets (design doc §6e/§7) ----

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

/** Upserts by appending a new version, matching §7b's "a write always claims a new version, never
 * overwrites an existing one" semantics -- a previously soft-deleted key becomes live again. */
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

/** {@code destroy}: hard-delete (removes the entry entirely); otherwise soft-delete (§7d), keeping
 * every version reachable by explicit version number. */
export function removeSecret(tenantId: string, key: string, destroy: boolean) {
  const list = secretsByTenant[tenantId];
  if (!list) return;
  const i = list.findIndex((s) => s.key === key);
  if (i < 0) return;
  if (destroy) list.splice(i, 1);
  else list[i].deleted = true;
}
