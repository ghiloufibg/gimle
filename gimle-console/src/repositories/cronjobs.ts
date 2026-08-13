import type { CronJob, CronJobSpecInput, Page } from "@/types";
import { addCronJob, cronJobs, removeCronJob } from "./fixture";
import { delay, paginate } from "./util";

export interface CronJobsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<CronJob>>;
  fetchOne(name: string): Promise<CronJob>;
  create(spec: CronJobSpecInput): Promise<CronJob>;
  remove(name: string): Promise<void>;
  /** Fires immediately, bypassing the schedule -- returns the generated Job's name. */
  trigger(name: string): Promise<string>;
}

export class MockCronJobsRepository implements CronJobsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(cronJobs, cursor, pageSize));
  }
  async fetchOne(name: string) {
    const c = cronJobs.find((x) => x.spec.name === name);
    if (!c) throw new Error(`CronJob not found: ${name}`);
    return delay(c);
  }
  async create(spec: CronJobSpecInput) {
    const c: CronJob = { spec, lastScheduleTime: null };
    addCronJob(c);
    return delay(c);
  }
  async remove(name: string) {
    removeCronJob(name);
    return delay(undefined);
  }
  async trigger(name: string) {
    const c = cronJobs.find((x) => x.spec.name === name);
    if (!c) throw new Error(`CronJob not found: ${name}`);
    return delay(`${name}-${Math.floor(Date.now() / 1000)}`);
  }
}
