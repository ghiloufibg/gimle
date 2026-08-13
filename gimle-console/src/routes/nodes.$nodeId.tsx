import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useNodesStore } from "@/stores/useNodesStore";
import { useInstancesStore } from "@/stores/useInstancesStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge, StatusDot } from "@/components/status";
import { Button } from "@/components/ui/button";
import { fmtBytes, fmtMillicores, fmtRelativeTime, isStale } from "@/lib/format";
import { InstancesTable, type InstancesTableFilters } from "@/components/instances-table";
import { FileText } from "lucide-react";

export const Route = createFileRoute("/nodes/$nodeId")({
  head: ({ params }) => ({
    meta: [
      { title: `${params.nodeId} — Node — Gimlé Console` },
      { name: "description", content: `Node detail for ${params.nodeId}.` },
      { property: "og:title", content: `${params.nodeId} — Gimlé Console` },
      { property: "og:description", content: `Node detail for ${params.nodeId}.` },
    ],
  }),
  component: NodeDetail,
});

function CapacityBar({
  label,
  used,
  total,
  format,
}: {
  label: string;
  used: number;
  total: number;
  format: (n: number) => string;
}) {
  const pct = Math.min(100, (used / total) * 100);
  const tone = pct > 85 ? "bad" : pct > 70 ? "warn" : "ok";
  const barColor =
    tone === "bad" ? "bg-status-bad" : tone === "warn" ? "bg-status-warn" : "bg-status-ok";
  return (
    <div className="rounded border border-border bg-card p-3">
      <div className="flex items-center justify-between text-xs mb-2">
        <span className="text-muted-foreground uppercase tracking-wider text-[10px]">{label}</span>
        <span className="font-mono">
          {format(used)} <span className="text-muted-foreground">/ {format(total)}</span>
        </span>
      </div>
      <div className="h-2 rounded bg-muted overflow-hidden">
        <div className={`h-full ${barColor}`} style={{ width: `${pct}%` }} />
      </div>
      <div className="mt-1 text-[10px] text-muted-foreground font-mono text-right">
        {pct.toFixed(0)}%
      </div>
    </div>
  );
}

function NodeDetail() {
  const { nodeId } = Route.useParams();
  const nodes = useNodesStore((s) => s.items);
  const getOrFetchNode = useNodesStore((s) => s.getOrFetch);
  const instances = useInstancesStore();
  const [filters, setFilters] = useState<InstancesTableFilters>({ nodeId });
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    getOrFetchNode(nodeId).catch(() => setNotFound(true));
    instances.setFilter({ nodeId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeId]);

  const n = nodes.find((x) => x.nodeId === nodeId);

  if (notFound) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Node not found.</p>
      </PageContainer>
    );
  }
  if (!n) {
    return (
      <PageContainer>
        <p className="text-sm text-muted-foreground">Loading node…</p>
      </PageContainer>
    );
  }

  const stale = isStale(n.lastHeartbeatAt);

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{n.nodeId}</span>}
        subtitle={
          <span className="inline-flex items-center gap-1.5">
            <StatusDot variant={stale ? "warn" : "ok"} />
            heartbeat {fmtRelativeTime(n.lastHeartbeatAt)}
          </span>
        }
        actions={
          <>
            <Button variant="outline" size="sm" asChild>
              <Link to="/nodes">Back</Link>
            </Button>
            <Button size="sm" asChild>
              <Link
                to="/logs"
                search={{ kind: "node", nodeId: n.nodeId, category: "PLATFORM" as const }}
              >
                <FileText className="h-4 w-4" />
                View logs
              </Link>
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
        <CapacityBar
          label="Memory (assigned/total)"
          used={n.capacity.assignedMemoryBytes}
          total={n.capacity.totalMemoryBytes}
          format={fmtBytes}
        />
        <CapacityBar
          label="CPU (assigned/total)"
          used={n.capacity.assignedCpuMillicores}
          total={n.capacity.totalCpuMillicores}
          format={fmtMillicores}
        />
      </div>

      <div className="mb-4 rounded border border-border bg-card p-3">
        <div className="text-[10px] uppercase tracking-wider text-muted-foreground mb-1">
          Supported tiers
        </div>
        <div className="flex gap-1">
          {n.capabilities.supportedTiers.map((t) => (
            <StatusBadge key={t} variant="info">
              {t}
            </StatusBadge>
          ))}
        </div>
      </div>

      <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        Instances on this node
      </div>
      <InstancesTable
        rows={instances.items}
        filters={filters}
        onFiltersChange={setFilters}
        hasMore={instances.hasMore}
        loading={instances.loading}
        onLoadMore={instances.loadMore}
        lockedNode={n.nodeId}
      />
    </PageContainer>
  );
}
