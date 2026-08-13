import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useJobsStore } from "@/stores/useJobsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/jobs/")({
  head: () => ({
    meta: [
      { title: "Jobs — Gimlé Console" },
      { name: "description", content: "All run-to-completion jobs across the cluster." },
      { property: "og:title", content: "Jobs — Gimlé Console" },
      { property: "og:description", content: "All run-to-completion jobs across the cluster." },
    ],
  }),
  component: JobsList,
});

// No "New job" button here (unlike DeploymentsList's own): a Job's manifest (backoffLimit,
// activeDeadline, the run-to-completion module itself) has no natural short create form yet --
// `gimle job apply -f <manifest.yaml>` is the supported creation path for now.
function JobsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh } = useJobsStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="Jobs"
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
              <th className="px-2 py-1.5 font-medium">Module</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium">Phase</th>
              <th className="px-2 py-1.5 font-medium text-right">Attempt</th>
              <th className="px-2 py-1.5 font-medium">Node</th>
            </tr>
          </thead>
          <tbody>
            {items.map((j) => (
              <tr key={j.spec.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/jobs/$name"
                    params={{ name: j.spec.name }}
                    className="text-primary hover:underline"
                  >
                    {j.spec.name}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {j.spec.moduleId.name}@{j.spec.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono">{j.spec.tenantId ?? "—"}</td>
                <td className="px-2 py-1.5">
                  <StatusBadge
                    variant={
                      j.phase === "SUCCEEDED" ? "ok" : j.phase === "FAILED" ? "bad" : "info"
                    }
                  >
                    {j.phase}
                  </StatusBadge>
                </td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {j.currentRun ? j.currentRun.attempt : "—"}
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {j.currentRun?.nodeId ?? "—"}
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
