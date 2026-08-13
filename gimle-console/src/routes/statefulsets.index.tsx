import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useStatefulSetsStore } from "@/stores/useStatefulSetsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/statefulsets/")({
  head: () => ({
    meta: [
      { title: "StatefulSets — Gimlé Console" },
      { name: "description", content: "All stable-identity workloads across the cluster." },
      { property: "og:title", content: "StatefulSets — Gimlé Console" },
      {
        property: "og:description",
        content: "All stable-identity workloads across the cluster.",
      },
    ],
  }),
  component: StatefulSetsList,
});

// No "New statefulset" button here, matching Job/CronJob/DaemonSet's own reasoning: `gimle apply -f
// <manifest.yaml>` is the supported creation path for now.
function StatefulSetsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh } = useStatefulSetsStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="StatefulSets"
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
              <th className="px-2 py-1.5 font-medium text-right">Replicas</th>
              <th className="px-2 py-1.5 font-medium">Health</th>
            </tr>
          </thead>
          <tbody>
            {items.map((s) => (
              <tr key={s.spec.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/statefulsets/$name"
                    params={{ name: s.spec.name }}
                    className="text-primary hover:underline"
                  >
                    {s.spec.name}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {s.spec.moduleId.name}@{s.spec.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono">{s.spec.tenantId ?? "—"}</td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {s.instances.length}/{s.spec.replicas}
                </td>
                <td className="px-2 py-1.5">
                  {s.unplacedCount > 0 ? (
                    <StatusBadge variant="bad">unplaced {s.unplacedCount}</StatusBadge>
                  ) : (
                    <StatusBadge variant="ok">healthy</StatusBadge>
                  )}
                </td>
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
