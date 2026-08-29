import type { Job, JobSpecInput, Page } from "@/types";
import { addJob, jobs, removeJob } from "./fixture";
import { delay, paginate } from "./util";

export interface JobsSummary {
  total: number;
  running: number;
  succeeded: number;
  failed: number;
  recent: Job[];
}

export interface JobsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<Job>>;
  fetchOne(name: string): Promise<Job>;
  fetchSummary(): Promise<JobsSummary>;
  create(spec: JobSpecInput): Promise<Job>;
  remove(name: string, tenantId?: string | null): Promise<void>;
}

export class MockJobsRepository implements JobsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(jobs, cursor, pageSize));
  }
  async fetchOne(name: string) {
    const j = jobs.find((x) => x.spec.name === name);
    if (!j) throw new Error(`Job not found: ${name}`);
    return delay(j);
  }
  async fetchSummary() {
    const summary: JobsSummary = {
      total: jobs.length,
      running: jobs.filter((j) => j.phase === "RUNNING").length,
      succeeded: jobs.filter((j) => j.phase === "SUCCEEDED").length,
      failed: jobs.filter((j) => j.phase === "FAILED").length,
      recent: jobs.slice(0, 8),
    };
    return delay(summary);
  }
  async create(spec: JobSpecInput) {
    const j: Job = { spec, phase: "RUNNING", currentRun: null };
    addJob(j);
    return delay(j);
  }
  async remove(name: string, _tenantId?: string | null) {
    // Fixture data is keyed by bare name only -- tenantId is accepted for interface parity with
    // HttpJobsRepository but unused here.
    removeJob(name);
    return delay(undefined);
  }
}
