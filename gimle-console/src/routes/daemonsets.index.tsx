import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useDaemonSetsStore } from "@/stores/useDaemonSetsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/daemonsets/")({
  head: () => ({
    meta: [
      { title: "DaemonSets — Gimlé Console" },
      { name: "description", content: "All node-scoped workloads across the cluster." },
      { property: "og:title", content: "DaemonSets — Gimlé Console" },
      {
        property: "og:description",
        content: "All node-scoped workloads across the cluster.",
      },
    ],
  }),
  component: DaemonSetsList,
});

// No "New daemonset" button here, matching JobsList/CronJobsList's own reasoning: `gimle apply -f
// <manifest.yaml>` is the supported creation path for now.
function DaemonSetsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh } = useDaemonSetsStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="DaemonSets"
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
              <th className="px-2 py-1.5 font-medium">Required labels</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium text-right">Nodes</th>
            </tr>
          </thead>
          <tbody>
            {items.map((d) => (
              <tr key={d.spec.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/daemonsets/$name"
                    params={{ name: d.spec.name }}
                    className="text-primary hover:underline"
                  >
                    {d.spec.name}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {d.spec.moduleId.name}@{d.spec.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono">
                  {d.spec.placement.requiredNodeLabels.length > 0
                    ? d.spec.placement.requiredNodeLabels.join(", ")
                    : "— (all nodes)"}
                </td>
                <td className="px-2 py-1.5 font-mono">{d.spec.tenantId ?? "—"}</td>
                <td className="px-2 py-1.5 font-mono text-right">{d.instances.length}</td>
              </tr>
            ))}
            {items.length === 0 && loading && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-muted-foreground">
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
