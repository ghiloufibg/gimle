import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useNodesStore } from "@/stores/useNodesStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge, StatusDot } from "@/components/status";
import { Button } from "@/components/ui/button";
import { fmtBytes, fmtMillicores, fmtRelativeTime, isStale } from "@/lib/format";

export const Route = createFileRoute("/nodes/")({
  head: () => ({
    meta: [
      { title: "Nodes — Gimlé Console" },
      { name: "description", content: "Cluster nodes and heartbeat status." },
      { property: "og:title", content: "Nodes — Gimlé Console" },
      { property: "og:description", content: "Cluster nodes and heartbeat status." },
    ],
  }),
  component: NodesList,
});

function NodesList() {
  const { items, loading, hasMore, loadFirstPage, loadMore, refresh } = useNodesStore();

  useEffect(() => {
    if (items.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="Nodes"
        subtitle={`${items.length} loaded`}
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
              <th className="px-2 py-1.5 font-medium">Node</th>
              <th className="px-2 py-1.5 font-medium">Tiers</th>
              <th className="px-2 py-1.5 font-medium">Heartbeat</th>
              <th className="px-2 py-1.5 font-medium text-right">CPU used</th>
              <th className="px-2 py-1.5 font-medium text-right">Mem used</th>
              <th className="px-2 py-1.5 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {items.map((n) => {
              const stale = isStale(n.lastHeartbeatAt);
              const cpuPct =
                (n.capacity.assignedCpuMillicores / n.capacity.totalCpuMillicores) * 100;
              const memPct = (n.capacity.assignedMemoryBytes / n.capacity.totalMemoryBytes) * 100;
              return (
                <tr key={n.nodeId} className="border-t border-border hover:bg-muted/30">
                  <td className="px-2 py-1.5 font-mono">
                    <Link
                      to="/nodes/$nodeId"
                      params={{ nodeId: n.nodeId }}
                      className="text-primary hover:underline"
                    >
                      {n.nodeId}
                    </Link>
                  </td>
                  <td className="px-2 py-1.5">
                    <div className="flex gap-1">
                      {n.capabilities.supportedTiers.map((t) => (
                        <StatusBadge key={t} variant="info">
                          {t}
                        </StatusBadge>
                      ))}
                    </div>
                  </td>
                  <td className="px-2 py-1.5 font-mono">
                    <span className="inline-flex items-center gap-1.5">
                      <StatusDot variant={stale ? "warn" : "ok"} />
                      {fmtRelativeTime(n.lastHeartbeatAt)}
                    </span>
                  </td>
                  <td className="px-2 py-1.5 font-mono text-right">{cpuPct.toFixed(0)}%</td>
                  <td className="px-2 py-1.5 font-mono text-right">{memPct.toFixed(0)}%</td>
                  <td className="px-2 py-1.5">
                    {stale ? (
                      <StatusBadge variant="warn">stale</StatusBadge>
                    ) : (
                      <StatusBadge variant="ok">healthy</StatusBadge>
                    )}
                  </td>
                </tr>
              );
            })}
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
