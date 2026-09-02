import type { CronJob, Job, Service } from "@/types";
import {
  applicationKey,
  instanceHealth,
  servicesFronting,
  type Application,
  type ApplicationCondition,
  type ApplicationInstance,
  type GeneratedJobSummary,
  type HealthStatus,
  type SyncStatus,
} from "@/addons/applications/model";

/**
 * The two run-to-completion kinds.
 *
 * Their health means something different from a long-running workload's: a Job's desired state is
 * *having run*, so a SUCCEEDED Job is healthy precisely because nothing of it is still running,
 * where a Deployment in that state would be broken. A CronJob has no run of its own at all -- it
 * is judged by the Jobs it generated.
 */

/** A CronJob's generated Job is named `<cronjob>-<epochSeconds>` by the reconciler that fires it,
 * so a Job belongs to a schedule when it carries that prefix, the same tenant, and a numeric
 * suffix -- never a bare prefix match, which would also catch a hand-applied `nightly-report-v2`. */
export function isGeneratedBy(job: Job, cronJob: CronJob): boolean {
  if ((job.spec.tenantId ?? null) !== (cronJob.spec.tenantId ?? null)) return false;
  const prefix = `${cronJob.spec.name}-`;
  if (!job.spec.name.startsWith(prefix)) return false;
  const suffix = job.spec.name.slice(prefix.length);
  return suffix.length > 0 && /^\d+$/.test(suffix);
}

/** The firing time encoded in a generated Job's own name, for ordering newest-first. */
function firingSecondOf(job: Job, cronJob: CronJob): number {
  return Number(job.spec.name.slice(cronJob.spec.name.length + 1));
}

function jobHealth(job: Job): HealthStatus {
  switch (job.phase) {
    case "SUCCEEDED":
      return "Healthy";
    case "FAILED":
      return "Degraded";
    case "RUNNING":
      return "Progressing";
  }
}

function runInstances(job: Job): ApplicationInstance[] {
  const run = job.currentRun;
  if (run === null || run.observation === null) return [];
  return [
    {
      id: `attempt-${run.attempt}`,
      label: `attempt ${run.attempt}`,
      // Every attempt of a Job is instance 0 of that Job -- the attempt number is not an index the
      // logs API addresses, so a row that linked to `instance N` would resolve to nothing.
      instanceIndex: 0,
      nodeId: run.nodeId,
      observation: run.observation,
      health: instanceHealth(run.observation),
    },
  ];
}

function jobConditions(job: Job): ApplicationCondition[] {
  const out: ApplicationCondition[] = [];
  const run = job.currentRun;
  if (job.phase === "FAILED") {
    const attempt = run === null ? null : run.attempt;
    out.push({
      severity: "bad",
      type: "RunFailed",
      message:
        attempt === null
          ? "the job failed with no attempt still recorded"
          : `attempt ${attempt} of ${job.spec.backoffLimit} failed, exhausting the backoff limit`,
    });
  }
  if (job.phase === "RUNNING" && run === null) {
    out.push({
      severity: "warn",
      type: "Unplaced",
      message: "the job is running but no attempt is currently placed on a node",
    });
  }
  if (run !== null && run.attempt > 1 && job.phase !== "FAILED") {
    out.push({
      severity: "warn",
      type: "Retrying",
      message: `on attempt ${run.attempt} of ${job.spec.backoffLimit} after an earlier failure`,
    });
  }
  return out;
}

/**
 * A Job converges on having run, so there is no replica count to compare: it is Synced from the
 * moment the control plane accepted it. The one exception is a Job the control plane believes is
 * running with nothing placed to run it -- desired state genuinely not reached yet.
 */
function jobSync(job: Job): SyncStatus {
  return job.phase === "RUNNING" && job.currentRun === null ? "OutOfSync" : "Synced";
}

export function fromJob(job: Job, services: readonly Service[]): Application {
  const tenantId = job.spec.tenantId;
  return {
    key: applicationKey("job", job.spec.name, tenantId),
    kind: "Job",
    kindLabel: "Job",
    name: job.spec.name,
    tenantId,
    moduleId: job.spec.moduleId,
    artifactPath: job.spec.artifactPath,
    instances: runInstances(job),
    services: servicesFronting(services, job.spec.name, tenantId),
    health: jobHealth(job),
    sync: jobSync(job),
    conditions: jobConditions(job),
    detail: {
      type: "job",
      phase: job.phase,
      attempt: job.currentRun?.attempt ?? null,
      backoffLimit: job.spec.backoffLimit,
    },
  };
}

/** How many generated Jobs a schedule shows before the rest are left to the Jobs screen. */
export const GENERATED_JOBS_SHOWN = 5;

export function fromCronJob(
  cronJob: CronJob,
  jobs: readonly Job[],
  services: readonly Service[],
): Application {
  const tenantId = cronJob.spec.tenantId;
  const generated = jobs
    .filter((j) => isGeneratedBy(j, cronJob))
    .sort((a, b) => firingSecondOf(b, cronJob) - firingSecondOf(a, cronJob));

  const summaries: GeneratedJobSummary[] = generated.slice(0, GENERATED_JOBS_SHOWN).map((j) => ({
    name: j.spec.name,
    health: jobHealth(j),
    phase: j.phase,
    attempt: j.currentRun?.attempt ?? null,
    nodeId: j.currentRun?.nodeId ?? null,
  }));

  const newest = generated[0];
  const conditions: ApplicationCondition[] = [];
  let health: HealthStatus;
  if (newest === undefined) {
    if (cronJob.lastScheduleTime !== null) {
      // It fired, yet nothing it generated is listed any more -- deleted, or never created.
      conditions.push({
        severity: "warn",
        type: "GeneratedJobMissing",
        message: `last fired ${cronJob.lastScheduleTime}, but no Job it generated is listed`,
      });
    }
    // A schedule that has not fired yet is not a problem to report: it is waiting, as asked.
    health = cronJob.lastScheduleTime === null ? "Healthy" : "Unknown";
  } else {
    health = jobHealth(newest);
    if (health === "Degraded") {
      conditions.push({
        severity: "bad",
        type: "LastRunFailed",
        message: `the most recent generated Job, ${newest.spec.name}, FAILED`,
      });
    }
  }

  return {
    key: applicationKey("cronjob", cronJob.spec.name, tenantId),
    kind: "CronJob",
    kindLabel: "CronJob",
    name: cronJob.spec.name,
    tenantId,
    moduleId: cronJob.spec.jobTemplate.moduleId,
    artifactPath: cronJob.spec.jobTemplate.artifactPath,
    // A CronJob never runs anything itself; its generated Jobs carry the instances.
    instances: [],
    services: servicesFronting(services, cronJob.spec.name, tenantId),
    health,
    // A schedule is accepted or it is not; there is no running state for it to diverge from.
    sync: "Synced",
    conditions,
    detail: {
      type: "cronjob",
      schedule: cronJob.spec.schedule,
      lastScheduleTime: cronJob.lastScheduleTime,
      concurrencyPolicy: cronJob.spec.concurrencyPolicy,
      generatedJobs: summaries,
    },
  };
}
