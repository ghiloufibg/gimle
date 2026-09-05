import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useNodesStore } from "@/stores/useNodesStore";
import { useInstancesStore } from "@/stores/useInstancesStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusBadge, StatusDot } from "@/components/status";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { fmtBytes, fmtMillicores, fmtRelativeTime, isStale } from "@/lib/format";
import { InstancesTable, type InstancesTableFilters } from "@/components/instances-table";
import { FileText, Ban, X } from "lucide-react";
import { toast } from "sonner";

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
  const setCordoned = useNodesStore((s) => s.setCordoned);
  const setTaint = useNodesStore((s) => s.setTaint);
  const instances = useInstancesStore();
  const tenants = useTenantsStore((s) => s.items);
  const loadTenants = useTenantsStore((s) => s.loadFirstPage);
  const [filters, setFilters] = useState<InstancesTableFilters>({ nodeId });
  const [notFound, setNotFound] = useState(false);
  const [taintTenantId, setTaintTenantId] = useState("");
  const [cordonPending, setCordonPending] = useState(false);

  useEffect(() => {
    getOrFetchNode(nodeId).catch(() => setNotFound(true));
    instances.setFilter({ nodeId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodeId]);

  useEffect(() => {
    if (tenants.length === 0) loadTenants();
  }, [tenants.length, loadTenants]);

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

  const stale = isStale(n.status);

  async function handleCordonToggle() {
    setCordonPending(true);
    try {
      const wasCordoned = n.cordoned;
      await setCordoned(nodeId, !wasCordoned);
      const err = useNodesStore.getState().error;
      if (err) toast.error(err);
      else toast.success(wasCordoned ? "Uncordoned" : "Cordoned");
    } finally {
      setCordonPending(false);
    }
  }

  async function handleAddTaint() {
    if (!taintTenantId) return;
    await setTaint(nodeId, taintTenantId, true);
    const err = useNodesStore.getState().error;
    if (err) toast.error(err);
    else {
      toast.success(`Tainted for ${taintTenantId}`);
      setTaintTenantId("");
    }
  }

  async function handleRemoveTaint(tenantId: string) {
    await setTaint(nodeId, tenantId, false);
    const err = useNodesStore.getState().error;
    if (err) toast.error(err);
    else toast.success(`Untainted for ${tenantId}`);
  }

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">{n.nodeId}</span>}
        subtitle={
          <span className="inline-flex items-center gap-1.5">
            <StatusDot variant={stale ? "warn" : "ok"} />
            heartbeat {fmtRelativeTime(n.lastHeartbeatAt)}
            {n.cordoned && <StatusBadge variant="warn">cordoned</StatusBadge>}
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
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button variant={n.cordoned ? "outline" : "destructive"} size="sm">
                  <Ban className="h-4 w-4" />
                  {n.cordoned ? "Uncordon" : "Cordon"}
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>
                    {n.cordoned ? "Uncordon" : "Cordon"} {n.nodeId}?
                  </AlertDialogTitle>
                  <AlertDialogDescription>
                    {n.cordoned
                      ? "This makes the node eligible for new scheduling decisions again."
                      : "This excludes the node from future scheduling decisions. It never evicts instances already running here."}
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel disabled={cordonPending}>Cancel</AlertDialogCancel>
                  <AlertDialogAction onClick={handleCordonToggle} disabled={cordonPending}>
                    {n.cordoned ? "Uncordon" : "Cordon"}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
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

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
        <div className="rounded border border-border bg-card p-3">
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

        <div className="rounded border border-border bg-card p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted-foreground mb-1">
            Taints (refuses new placements for these tenants)
          </div>
          <div className="mb-2 flex flex-wrap gap-1">
            {n.taints.length === 0 && <span className="text-xs text-muted-foreground">none</span>}
            {n.taints.map((tenantId) => (
              <span
                key={tenantId}
                className="inline-flex items-center gap-1 rounded bg-muted px-1.5 py-0.5 font-mono text-[11px]"
              >
                {tenantId}
                <button
                  onClick={() => handleRemoveTaint(tenantId)}
                  className="text-muted-foreground hover:text-status-bad"
                  aria-label={`Remove taint for ${tenantId}`}
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Select value={taintTenantId} onValueChange={setTaintTenantId}>
              <SelectTrigger className="h-7 flex-1 text-xs">
                <SelectValue placeholder="Pick tenant" />
              </SelectTrigger>
              <SelectContent>
                {tenants
                  .filter((t) => !n.taints.includes(t.id))
                  .map((t) => (
                    <SelectItem key={t.id} value={t.id} className="font-mono">
                      {t.id}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
            <Button
              size="sm"
              variant="outline"
              className="h-7 px-2 text-[11px]"
              disabled={!taintTenantId}
              onClick={handleAddTaint}
            >
              Add taint
            </Button>
          </div>
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
