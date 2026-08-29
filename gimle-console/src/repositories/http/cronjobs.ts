import type { CronJob, CronJobSpecInput, Page } from "@/types";
import type { CronJobsRepository } from "@/repositories/cronjobs";
import {
  requestJson,
  requestJsonWithBody,
  requestOk,
  requestOkYaml,
  tenantQuery,
} from "./apiClient";

// Wire shape -- mirrors ApiServer.java's cronJobStatus()/handleCronJobsList serialization.
interface RawCronJob {
  spec: {
    name: string;
    schedule: string;
    jobTemplate: {
      moduleId: { name: string; version: string };
      artifactPath: string;
      activeDeadlineSeconds?: number;
      backoffLimit: number;
    };
    startingDeadlineSeconds?: number;
    concurrencyPolicy: CronJob["spec"]["concurrencyPolicy"];
    tenantId?: string | null;
  };
  lastScheduleTime?: string;
}

function mapCronJob(raw: RawCronJob): CronJob {
  return {
    spec: { ...raw.spec, tenantId: raw.spec.tenantId ?? null },
    lastScheduleTime: raw.lastScheduleTime ?? null,
  };
}

function toManifestYaml(spec: CronJobSpecInput): string {
  // Hand-rolled, not a YAML library -- mirrors HttpJobsRepository's own toManifestYaml exactly.
  const q = (s: string) => JSON.stringify(s);
  const lines = [
    `kind: CronJob`,
    `name: ${q(spec.name)}`,
    `schedule: ${q(spec.schedule)}`,
    `jobTemplate:`,
    `  module:`,
    `    name: ${q(spec.jobTemplate.moduleId.name)}`,
    `    version: ${q(spec.jobTemplate.moduleId.version)}`,
    `  artifactPath: ${q(spec.jobTemplate.artifactPath)}`,
    `  backoffLimit: ${spec.jobTemplate.backoffLimit}`,
  ];
  if (spec.jobTemplate.activeDeadlineSeconds !== undefined) {
    lines.push(`  activeDeadlineSeconds: ${spec.jobTemplate.activeDeadlineSeconds}`);
  }
  if (spec.startingDeadlineSeconds !== undefined) {
    lines.push(`startingDeadlineSeconds: ${spec.startingDeadlineSeconds}`);
  }
  lines.push(`concurrencyPolicy: ${spec.concurrencyPolicy}`);
  if (spec.tenantId) lines.push(`tenantId: ${q(spec.tenantId)}`);
  return lines.join("\n") + "\n";
}

export class HttpCronJobsRepository implements CronJobsRepository {
  private cache: CronJob[] | null = null;

  async all(forceRefresh: boolean): Promise<CronJob[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawCronJob[]>("GET", "/cronjobs");
      this.cache = raw.map(mapCronJob);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<CronJob>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  // No known tenantId is available on a cache miss (the whole point of fetchOne is that the item
  // isn't in the already-loaded list) and no route currently threads one in from its own URL, so
  // this stays scoped to the untenanted namespace -- a tenanted cronjob reached this way still
  // 404s.
  async fetchOne(name: string): Promise<CronJob> {
    const raw = await requestJson<RawCronJob>("GET", `/cronjobs/${encodeURIComponent(name)}`);
    return mapCronJob(raw);
  }

  async create(spec: CronJobSpecInput): Promise<CronJob> {
    await requestOkYaml(`/cronjobs/${encodeURIComponent(spec.name)}`, toManifestYaml(spec));
    this.cache = null;
    return this.fetchOne(spec.name);
  }

  async remove(name: string, tenantId?: string | null): Promise<void> {
    await requestOk("DELETE", `/cronjobs/${encodeURIComponent(name)}${tenantQuery(tenantId)}`);
    this.cache = null;
  }

  async trigger(name: string): Promise<string> {
    const result = await requestJsonWithBody<{ jobName: string }>(
      "POST",
      `/cronjobs/${encodeURIComponent(name)}/trigger`,
      {},
    );
    this.cache = null;
    return result.jobName;
  }
}
