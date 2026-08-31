import type {
  ControllerRevision,
  DaemonSet,
  DaemonSetInstance,
  DaemonSetSpecInput,
  Page,
} from "@/types";
import type { DaemonSetsRepository } from "@/repositories/daemonsets";
import {
  requestJson,
  requestJsonWithBody,
  requestOk,
  requestOkYaml,
  tenantQuery,
} from "./apiClient";

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
    errorRatePerSecond: number;
    queueDepth: number;
    cpuMillicoresUsed: number;
    memoryBytesUsed: number;
    workerId?: string;
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
  errorRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: null,
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
      observation: i.observation
        ? ({
            ...i.observation,
            workerId: i.observation.workerId ?? null,
          } as DaemonSetInstance["observation"])
        : UNOBSERVED,
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
  // No `antiAffinity` line, ever: DaemonSetManifestParser rejects the key outright if present --
  // one-per-node placement makes anti-affinity meaningless.
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

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted daemonset reached this way still
  // 404s.
  async fetchOne(name: string): Promise<DaemonSet> {
    const raw = await requestJson<RawDaemonSet>("GET", `/daemonsets/${encodeURIComponent(name)}`);
    return mapDaemonSet(raw);
  }

  async create(spec: DaemonSetSpecInput): Promise<DaemonSet> {
    await requestOkYaml(`/daemonsets/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null;
    return this.fetchOne(spec.name);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/daemonsets/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
    this.cache = null;
  }

  async fetchRevisions(name: string, tenantId?: string | null): Promise<ControllerRevision[]> {
    const raw = await requestJson<{ revisions: ControllerRevision[] }>(
      "GET",
      `/daemonsets/${encodeURIComponent(name)}/revisions${tenantQuery(tenantId)}`,
    );
    return raw.revisions;
  }

  async rollback(
    name: string,
    toRevision?: number,
    tenantId?: string | null,
  ): Promise<ControllerRevision> {
    const rev = await requestJsonWithBody<ControllerRevision>(
      "POST",
      `/daemonsets/${encodeURIComponent(name)}/rollback${tenantQuery(tenantId)}`,
      toRevision === undefined ? {} : { toRevision },
    );
    this.cache = null;
    return rev;
  }
}
