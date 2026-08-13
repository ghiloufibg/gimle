import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useCronJobsStore } from "@/stores/useCronJobsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/cronjobs/")({
  head: () => ({
    meta: [
      { title: "CronJobs — Gimlé Console" },
      { name: "description", content: "All scheduled job generators across the cluster." },
      { property: "og:title", content: "CronJobs — Gimlé Console" },
      {
        property: "og:description",
        content: "All scheduled job generators across the cluster.",
      },
    ],
  }),
  component: CronJobsList,
});

// No "New cronjob" button here, matching JobsList's own reasoning: a CronJob manifest's shape
// (schedule, jobTemplate, concurrencyPolicy) has no natural short create form yet -- `gimle
// cronjob apply -f <manifest.yaml>` is the supported creation path for now.
function CronJobsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh } = useCronJobsStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="CronJobs"
        subtitle={`${items.length} loaded${hasMore ? " · more available" : ""}`}
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
            Refresh
          </Button>
        }
      />
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Name</th>
              <th className="px-2 py-1.5 font-medium">Schedule</th>
              <th className="px-2 py-1.5 font-medium">Module</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium">Concurrency</th>
              <th className="px-2 py-1.5 font-medium">Last fired</th>
            </tr>
          </thead>
          <tbody>
            {items.map((c) => (
              <tr key={c.spec.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/cronjobs/$name"
                    params={{ name: c.spec.name }}
                    className="text-primary hover:underline"
                  >
                    {c.spec.name}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono">{c.spec.schedule}</td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {c.spec.jobTemplate.moduleId.name}@{c.spec.jobTemplate.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono">{c.spec.tenantId ?? "—"}</td>
                <td className="px-2 py-1.5">
                  <StatusBadge variant={c.spec.concurrencyPolicy === "FORBID" ? "warn" : "info"}>
                    {c.spec.concurrencyPolicy}
                  </StatusBadge>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {c.lastScheduleTime ?? "never"}
                </td>
              </tr>
            ))}
            {items.length === 0 && loading && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-muted-foreground">
                  Loading…
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="mt-3 flex justify-center">
        {hasMore ? (
          <Button variant="outline" size="sm" onClick={() => loadMore()} disabled={loading}>
            {loading ? "Loading…" : "Load more"}
          </Button>
        ) : (
          <span className="text-xs text-muted-foreground">— end of list —</span>
        )}
      </div>
    </PageContainer>
  );
}
