import type {
  AutoscalePolicy,
  Deployment,
  DeploymentInstance,
  DeploymentSpecInput,
  DisruptionBudget,
  Page,
} from "@/types";
import type { DeploymentsRepository, DeploymentsSummary } from "@/repositories/deployments";
import { requestJson, requestOk, requestOkYaml, tenantQuery } from "./apiClient";

// Wire shapes -- mirrors ApiServer.java's deploymentStatus()/handleDeploymentsList serialization.
// `instances[].observation` and `spec.tenantId` are both optional on the wire (an instance can be
// placed before its first heartbeat; a deployment can be untenanted) -- normalized to this project's
// non-optional frontend types below.
interface RawDeploymentInstance {
  instanceIndex: number;
  nodeId: string;
  observation?: {
    lifecycleState: string;
    alive: boolean;
    ready: boolean;
    requestRatePerSecond: number;
    queueDepth: number;
    cpuMillicoresUsed: number;
    memoryBytesUsed: number;
    workerId?: string;
  };
}
interface RawDeployment {
  spec: {
    name: string;
    moduleId: { name: string; version: string };
    artifactPath: string;
    replicas: number;
    tenantId?: string | null;
    autoscale?: AutoscalePolicy;
    disruption?: DisruptionBudget;
  };
  instances: RawDeploymentInstance[];
  unplacedCount: number;
  quotaViolating: boolean;
  limitRangeViolating: boolean;
  limitRangeViolationReason?: string;
}

const UNOBSERVED: DeploymentInstance["observation"] = {
  lifecycleState: "INSTALLED",
  alive: false,
  ready: false,
  requestRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: null,
};

function mapDeployment(raw: RawDeployment): Deployment {
  return {
    spec: { ...raw.spec, tenantId: raw.spec.tenantId ?? null },
    instances: raw.instances.map((i) => ({
      instanceIndex: i.instanceIndex,
      nodeId: i.nodeId,
      observation: i.observation
        ? ({
            ...i.observation,
            workerId: i.observation.workerId ?? null,
          } as DeploymentInstance["observation"])
        : UNOBSERVED,
    })),
    unplacedCount: raw.unplacedCount,
    quotaViolating: raw.quotaViolating,
    limitRangeViolating: raw.limitRangeViolating,
    limitRangeViolationReason: raw.limitRangeViolationReason,
  };
}

/** Emits `key: value` at the given indent only when value is set -- every autoscale field past
 * the three required ones is optional on the wire (DeploymentManifestParser.parseAutoscale). */
function optionalNumberLine(indent: number, key: string, value: number | undefined): string[] {
  return value === undefined ? [] : [`${" ".repeat(indent)}${key}: ${value}`];
}

function autoscaleYaml(a: AutoscalePolicy): string[] {
  const lines = [
    "autoscale:",
    `  minReplicas: ${a.minReplicas}`,
    `  maxReplicas: ${a.maxReplicas}`,
    `  targetCpuUtilizationPercent: ${a.targetCpuUtilizationPercent}`,
    ...optionalNumberLine(2, "targetRequestRatePerSecond", a.targetRequestRatePerSecond),
    ...optionalNumberLine(2, "targetErrorRatePercent", a.targetErrorRatePercent),
    ...optionalNumberLine(2, "targetQueueDepth", a.targetQueueDepth),
    `  mode: ${a.combinationMode === "WEIGHTED" ? "weighted" : "worst-signal"}`,
  ];
  if (a.combinationMode === "WEIGHTED") {
    lines.push(
      ...optionalNumberLine(2, "cpuWeight", a.cpuWeight),
      ...optionalNumberLine(2, "requestRateWeight", a.requestRateWeight),
      ...optionalNumberLine(2, "errorRateWeight", a.errorRateWeight),
      ...optionalNumberLine(2, "queueDepthWeight", a.queueDepthWeight),
    );
  }
  return lines;
}

function disruptionYaml(d: DisruptionBudget): string[] {
  return ["disruption:", `  maxUnavailable: ${d.maxUnavailable}`, `  maxSurge: ${d.maxSurge}`];
}

function toManifestYaml(spec: DeploymentSpecInput): string {
  // Hand-rolled, not a YAML library: known fields, fixed shape -- matches this project's
  // "hand-roll it, it's small" convention (gimle-core's own Json.java). Double-quoted scalars via
  // JSON.stringify's escaping are valid YAML string syntax, so this is safe against special
  // characters in names/paths without needing a real YAML serializer.
  const q = (s: string) => JSON.stringify(s);
  const lines = [
    // Every manifest now requires kind: -- the control plane rejects a PUT /deployments/* body
    // without it.
    `kind: Deployment`,
    `name: ${q(spec.name)}`,
    `module:`,
    `  name: ${q(spec.moduleId.name)}`,
    `  version: ${q(spec.moduleId.version)}`,
    `replicas: ${spec.replicas}`,
  ];
  // Omitted entirely, not sent blank: ManifestFields.optionalArtifactPath treats a *present but
  // blank* artifactPath as a manifest error ("omit it entirely to resolve..."), and only an
  // absent key resolves the module's (name, version) coordinate from the Andvari registry.
  if (spec.artifactPath.trim() !== "") {
    lines.push(`artifactPath: ${q(spec.artifactPath)}`);
  }
  if (spec.tenantId) lines.push(`tenantId: ${q(spec.tenantId)}`);
  if (spec.autoscale) lines.push(...autoscaleYaml(spec.autoscale));
  if (spec.disruption) lines.push(...disruptionYaml(spec.disruption));
  return lines.join("\n") + "\n";
}

export class HttpDeploymentsRepository implements DeploymentsRepository {
  private cache: Deployment[] | null = null;

  /** Shared with HttpInstancesRepository so both screens read the same snapshot -- avoids two
   * independent GET /deployments calls that could race and briefly disagree. */
  async all(forceRefresh: boolean): Promise<Deployment[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawDeployment[]>("GET", "/deployments");
      this.cache = raw.map(mapDeployment);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<Deployment>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted deployment reached this way still
  // 404s. Fixing that needs a `?tenant=` param on the detail route itself, not invented here.
  async fetchOne(name: string): Promise<Deployment> {
    const raw = await requestJson<RawDeployment>("GET", `/deployments/${encodeURIComponent(name)}`);
    return mapDeployment(raw);
  }

  async fetchSummary(): Promise<DeploymentsSummary> {
    // No summary endpoint exists -- the backend has no pagination either, so `all()` is already the
    // full cluster-wide array; aggregate over it exactly like the mock does.
    const all = await this.all(false);
    return {
      total: all.length,
      unplacedInstances: all.reduce((s, d) => s + d.unplacedCount, 0),
      quotaViolating: all.filter((d) => d.quotaViolating).length,
      recent: all.slice(0, 8),
    };
  }

  async create(spec: DeploymentSpecInput): Promise<Deployment> {
    await requestOkYaml(`/deployments/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null; // bust: the fetchOne below re-populates it fresh on next `all()` call
    return this.fetchOne(spec.name);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/deployments/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
    this.cache = null;
  }
}
