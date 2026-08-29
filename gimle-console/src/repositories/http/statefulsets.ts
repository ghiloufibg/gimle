import type { Page, StatefulSet, StatefulSetInstance, StatefulSetSpecInput } from "@/types";
import type { StatefulSetsRepository } from "@/repositories/statefulsets";
import { requestJson, requestOk, requestOkYaml, tenantQuery } from "./apiClient";

// Wire shape -- mirrors ApiServer.java's statefulSetStatus()/handleStatefulSetsList serialization.
// `instances[].observation` and `spec.tenantId` are both optional on the wire (an index can be
// placed before its first heartbeat; a statefulset can be untenanted) -- normalized to this
// project's non-optional frontend types below, the same convention HttpDeploymentsRepository uses.
interface RawStatefulSetInstance {
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
interface RawStatefulSet {
  spec: {
    name: string;
    moduleId: { name: string; version: string };
    artifactPath: string;
    replicas: number;
    tenantId?: string | null;
  };
  instances: RawStatefulSetInstance[];
  unplacedCount: number;
}

const UNOBSERVED: StatefulSetInstance["observation"] = {
  lifecycleState: "INSTALLED",
  alive: false,
  ready: false,
  requestRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: null,
};

function mapStatefulSet(raw: RawStatefulSet): StatefulSet {
  return {
    spec: { ...raw.spec, tenantId: raw.spec.tenantId ?? null },
    instances: raw.instances.map((i) => ({
      instanceIndex: i.instanceIndex,
      nodeId: i.nodeId,
      observation: i.observation
        ? ({
            ...i.observation,
            workerId: i.observation.workerId ?? null,
          } as StatefulSetInstance["observation"])
        : UNOBSERVED,
    })),
    unplacedCount: raw.unplacedCount,
  };
}

function toManifestYaml(spec: StatefulSetSpecInput): string {
  // Hand-rolled, not a YAML library -- mirrors HttpDeploymentsRepository's own toManifestYaml.
  const q = (s: string) => JSON.stringify(s);
  const lines = [
    `kind: StatefulSet`,
    `name: ${q(spec.name)}`,
    `module:`,
    `  name: ${q(spec.moduleId.name)}`,
    `  version: ${q(spec.moduleId.version)}`,
    `artifactPath: ${q(spec.artifactPath)}`,
    `replicas: ${spec.replicas}`,
  ];
  if (spec.tenantId) lines.push(`tenantId: ${q(spec.tenantId)}`);
  return lines.join("\n") + "\n";
}

export class HttpStatefulSetsRepository implements StatefulSetsRepository {
  private cache: StatefulSet[] | null = null;

  async all(forceRefresh: boolean): Promise<StatefulSet[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawStatefulSet[]>("GET", "/statefulsets");
      this.cache = raw.map(mapStatefulSet);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<StatefulSet>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted statefulset reached this way still
  // 404s.
  async fetchOne(name: string): Promise<StatefulSet> {
    const raw = await requestJson<RawStatefulSet>(
      "GET",
      `/statefulsets/${encodeURIComponent(name)}`,
    );
    return mapStatefulSet(raw);
  }

  async create(spec: StatefulSetSpecInput): Promise<StatefulSet> {
    await requestOkYaml(`/statefulsets/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null;
    return this.fetchOne(spec.name);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/statefulsets/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
    this.cache = null;
  }
}
