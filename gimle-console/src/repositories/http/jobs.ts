import type { Job, JobRun, JobSpecInput, Page } from "@/types";
import type { JobsRepository, JobsSummary } from "@/repositories/jobs";
import { requestJson, requestOk, requestOkYaml, tenantQuery } from "./apiClient";

// Wire shape -- mirrors ApiServer.java's jobStatus()/handleJobsList serialization.
interface RawJob {
  spec: {
    name: string;
    moduleId: { name: string; version: string };
    artifactPath: string;
    activeDeadlineSeconds?: number;
    backoffLimit: number;
    tenantId?: string | null;
  };
  phase: Job["phase"];
  currentRun?: {
    attempt: number;
    nodeId: string;
    observation?: JobRun["observation"];
  };
}

function mapJob(raw: RawJob): Job {
  return {
    spec: { ...raw.spec, tenantId: raw.spec.tenantId ?? null },
    phase: raw.phase,
    currentRun: raw.currentRun
      ? {
          attempt: raw.currentRun.attempt,
          nodeId: raw.currentRun.nodeId,
          observation: raw.currentRun.observation ?? null,
        }
      : null,
  };
}

function toManifestYaml(spec: JobSpecInput): string {
  // Hand-rolled, not a YAML library -- mirrors HttpDeploymentsRepository's own toManifestYaml
  // exactly, including the JSON.stringify-for-YAML-string-escaping trick.
  const q = (s: string) => JSON.stringify(s);
  const lines = [
    `kind: Job`,
    `name: ${q(spec.name)}`,
    `module:`,
    `  name: ${q(spec.moduleId.name)}`,
    `  version: ${q(spec.moduleId.version)}`,
    `artifactPath: ${q(spec.artifactPath)}`,
    `backoffLimit: ${spec.backoffLimit}`,
  ];
  if (spec.activeDeadlineSeconds !== undefined) {
    lines.push(`activeDeadlineSeconds: ${spec.activeDeadlineSeconds}`);
  }
  if (spec.tenantId) lines.push(`tenantId: ${q(spec.tenantId)}`);
  return lines.join("\n") + "\n";
}

export class HttpJobsRepository implements JobsRepository {
  private cache: Job[] | null = null;

  async all(forceRefresh: boolean): Promise<Job[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawJob[]>("GET", "/jobs");
      this.cache = raw.map(mapJob);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<Job>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted job reached this way still 404s.
  async fetchOne(name: string): Promise<Job> {
    const raw = await requestJson<RawJob>("GET", `/jobs/${encodeURIComponent(name)}`);
    return mapJob(raw);
  }

  async fetchSummary(): Promise<JobsSummary> {
    // No summary endpoint exists, same as HttpDeploymentsRepository -- aggregate over the full
    // cluster-wide array, mirroring the mock's own aggregation exactly.
    const all = await this.all(false);
    return {
      total: all.length,
      running: all.filter((j) => j.phase === "RUNNING").length,
      succeeded: all.filter((j) => j.phase === "SUCCEEDED").length,
      failed: all.filter((j) => j.phase === "FAILED").length,
      recent: all.slice(0, 8),
    };
  }

  async create(spec: JobSpecInput): Promise<Job> {
    await requestOkYaml(`/jobs/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null;
    return this.fetchOne(spec.name);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/jobs/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
    this.cache = null;
  }
}
