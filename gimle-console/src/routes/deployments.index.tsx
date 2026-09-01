import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useAutoRefresh } from "@/hooks/use-auto-refresh";
import { useDeploymentsStore } from "@/stores/useDeploymentsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge } from "@/components/status";
import { Button } from "@/components/ui/button";
import { isDeploymentHealthy } from "@/lib/deployment-health";
import { Plus } from "lucide-react";

export const Route = createFileRoute("/deployments/")({
  head: () => ({
    meta: [
      { title: "Deployments — Gimlé Console" },
      { name: "description", content: "All module deployments across the cluster." },
      { property: "og:title", content: "Deployments — Gimlé Console" },
      { property: "og:description", content: "All module deployments across the cluster." },
    ],
  }),
  component: DeploymentsList,
});

function DeploymentsList() {
  const { items, hasMore, loading, loadFirstPage, loadMore, refresh, poll } = useDeploymentsStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useAutoRefresh(poll);

  return (
    <PageContainer>
      <PageHeader
        title="Deployments"
        subtitle={`${items.length} loaded${hasMore ? " · more available" : ""}`}
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
              Refresh
            </Button>
            <Button size="sm" asChild>
              <Link to="/deployments/new">
                <Plus className="h-4 w-4" />
                New deployment
              </Link>
            </Button>
          </>
        }
      />
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Name</th>
              <th className="px-2 py-1.5 font-medium">Module</th>
              <th className="px-2 py-1.5 font-medium">Artifact</th>
              <th className="px-2 py-1.5 font-medium">Tenant</th>
              <th className="px-2 py-1.5 font-medium text-right">Replicas</th>
              <th className="px-2 py-1.5 font-medium">Health</th>
            </tr>
          </thead>
          <tbody>
            {items.map((d) => (
              <tr key={d.spec.name} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/deployments/$name"
                    params={{ name: d.spec.name }}
                    className="text-primary hover:underline"
                  >
                    {d.spec.name}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {d.spec.moduleId.name}@{d.spec.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground truncate max-w-[280px]">
                  {d.spec.artifactPath}
                </td>
                <td className="px-2 py-1.5 font-mono">{d.spec.tenantId ?? "—"}</td>
                <td className="px-2 py-1.5 font-mono text-right">
                  {d.instances.length}/{d.spec.replicas}
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex flex-wrap gap-1">
                    {d.unplacedCount > 0 && (
                      <StatusBadge variant="bad">unplaced {d.unplacedCount}</StatusBadge>
                    )}
                    {d.quotaViolating && <StatusBadge variant="bad">quota</StatusBadge>}
                    {d.limitRangeViolating && (
                      <StatusBadge variant="bad" title={d.limitRangeViolationReason}>
                        limit range
                      </StatusBadge>
                    )}
                    {d.unplacedCount === 0 &&
                      !d.quotaViolating &&
                      !d.limitRangeViolating &&
                      !isDeploymentHealthy(d) && <StatusBadge variant="bad">unhealthy</StatusBadge>}
                    {isDeploymentHealthy(d) && <StatusBadge variant="ok">healthy</StatusBadge>}
                  </div>
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
