import type { DaemonSet, DaemonSetInstance, DaemonSetSpecInput, Page } from "@/types";
import type { DaemonSetsRepository } from "@/repositories/daemonsets";
import { requestJson, requestOk, requestOkYaml } from "./apiClient";

// Wire shape -- mirrors ApiServer.java's daemonSetStatus()/handleDaemonSetsList serialization.
// `instances[].observation` and `spec.tenantId` are both optional on the wire (an assignment can
// be placed before its first heartbeat; a daemonset can be untenanted) -- normalized to this
// project's non-optional frontend types below, same as HttpDeploymentsRepository.
interface RawDaemonSetInstance {
  nodeId: string;
  observation?: {
    lifecycleState: string;
    alive: boolean;
    ready: boolean;
    requestRatePerSecond: number;
    queueDepth: number;
    cpuMillicoresUsed: number;
    memoryBytesUsed: number;
  };
}
interface RawDaemonSet {
  spec: {
    name: string;
    moduleId: { name: string; version: string };
    artifactPath: string;
    placement: { requiredLabels?: string[] };
    tenantId?: string | null;
  };
  instances: RawDaemonSetInstance[];
}

const UNOBSERVED: DaemonSetInstance["observation"] = {
  lifecycleState: "INSTALLED",
  alive: false,
  ready: false,
  requestRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
};

function mapDaemonSet(raw: RawDaemonSet): DaemonSet {
  return {
    spec: {
      name: raw.spec.name,
      moduleId: raw.spec.moduleId,
      artifactPath: raw.spec.artifactPath,
      placement: { requiredNodeLabels: raw.spec.placement.requiredLabels ?? [] },
      tenantId: raw.spec.tenantId ?? null,
    },
    instances: raw.instances.map((i) => ({
      nodeId: i.nodeId,
      observation: (i.observation ?? UNOBSERVED) as DaemonSetInstance["observation"],
    })),
  };
}

function toManifestYaml(spec: DaemonSetSpecInput): string {
  // Hand-rolled, not a YAML library -- mirrors HttpDeploymentsRepository's own toManifestYaml.
  const q = (s: string) => JSON.stringify(s);
  const lines = [
    `kind: DaemonSet`,
    `name: ${q(spec.name)}`,
    `module:`,
    `  name: ${q(spec.moduleId.name)}`,
    `  version: ${q(spec.moduleId.version)}`,
    `artifactPath: ${q(spec.artifactPath)}`,
  ];
  // No `antiAffinity` line, ever: DaemonSetManifestParser rejects the key outright if present
  // (priority-3 design doc §4d) -- one-per-node placement makes anti-affinity meaningless.
  if (spec.placement.requiredNodeLabels.length > 0) {
    lines.push(`placement:`);
    lines.push(`  requiredLabels: [${spec.placement.requiredNodeLabels.map(q).join(", ")}]`);
  }
  if (spec.tenantId) lines.push(`tenantId: ${q(spec.tenantId)}`);
  return lines.join("\n") + "\n";
}

export class HttpDaemonSetsRepository implements DaemonSetsRepository {
  private cache: DaemonSet[] | null = null;

  async all(forceRefresh: boolean): Promise<DaemonSet[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawDaemonSet[]>("GET", "/daemonsets");
      this.cache = raw.map(mapDaemonSet);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<DaemonSet>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  async fetchOne(name: string): Promise<DaemonSet> {
    const raw = await requestJson<RawDaemonSet>("GET", `/daemonsets/${encodeURIComponent(name)}`);
    return mapDaemonSet(raw);
  }

  async create(spec: DaemonSetSpecInput): Promise<DaemonSet> {
    await requestOkYaml(`/daemonsets/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null;
    return this.fetchOne(spec.name);
  }

  async remove(name: string): Promise<void> {
    await requestOk("DELETE", `/daemonsets/${encodeURIComponent(name)}`);
    this.cache = null;
  }
}
