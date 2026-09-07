import type { RunCronJob } from "@/repositories";

const PHASE_CLASS: Record<string, string> = {
  RUNNING: "bg-status-info-bg text-status-info",
  SUCCEEDED: "bg-status-ok-bg text-status-ok",
  FAILED: "bg-status-bad-bg text-status-bad",
};

function formatFiringTime(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

export function RunCronJobs({ cronJobs }: { cronJobs: RunCronJob[] }) {
  if (cronJobs.length === 0)
    return <p className="mt-1 text-[10px] text-muted-foreground">No CronJobs in this blueprint.</p>;

  return (
    <ul className="mt-1 space-y-2">
      {cronJobs.map((cronJob) => (
        <li key={cronJob.name}>
          <div className="font-mono text-[11px] text-foreground">{cronJob.name}</div>
          {cronJob.jobs.length === 0 ? (
            <p className="text-[10px] text-muted-foreground">No firings yet.</p>
          ) : (
            <ul className="mt-0.5 space-y-0.5 pl-2">
              {cronJob.jobs.map((job) => (
                <li key={job.name} className="flex items-center justify-between gap-2">
                  <span className="num truncate text-[10px] text-muted-foreground" title={job.name}>
                    {formatFiringTime(job.firingTime)}
                  </span>
                  <span
                    className={`shrink-0 rounded-sm px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest ${
                      PHASE_CLASS[job.phase] ?? "bg-status-muted/20 text-status-muted"
                    }`}
                  >
                    {job.phase}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}
