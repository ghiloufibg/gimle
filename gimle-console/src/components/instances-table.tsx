import { Link } from "@tanstack/react-router";
import { useMemo } from "react";
import type { LifecycleState, ModuleInstance } from "@/types";
import { LifecycleBadge, StatusDot } from "@/components/status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { fmtBytes, fmtMillicores } from "@/lib/format";
import { FileText } from "lucide-react";

export interface InstancesTableFilters {
  deployment?: string;
  nodeId?: string;
  tenantId?: string;
  lifecycle?: LifecycleState | "ALL";
}

interface Props {
  rows: ModuleInstance[];
  filters: InstancesTableFilters;
  onFiltersChange: (f: InstancesTableFilters) => void;
  hasMore: boolean;
  loading: boolean;
  onLoadMore: () => void;
  lockedNode?: string;
  showFilters?: boolean;
}

const LIFECYCLES: LifecycleState[] = [
  "INSTALLED",
  "RESOLVED",
  "STARTING",
  "ACTIVE",
  "STOPPING",
  "UNINSTALLED",
  "FAILED",
];

export function InstancesTable({
  rows,
  filters,
  onFiltersChange,
  hasMore,
  loading,
  onLoadMore,
  lockedNode,
  showFilters = true,
}: Props) {
  const uniqueNodes = useMemo(() => Array.from(new Set(rows.map((r) => r.nodeId))).sort(), [rows]);
  const uniqueTenants = useMemo(
    () => Array.from(new Set(rows.map((r) => r.tenantId).filter((x): x is string => !!x))).sort(),
    [rows],
  );

  return (
    <div className="flex flex-col gap-3">
      {showFilters && (
        <div className="flex flex-wrap items-center gap-2">
          <Input
            placeholder="Filter deployment…"
            value={filters.deployment ?? ""}
            onChange={(e) => onFiltersChange({ ...filters, deployment: e.target.value || undefined })}
            className="h-8 w-56 font-mono text-xs"
          />
          {!lockedNode && (
            <Select
              value={filters.nodeId ?? "ALL"}
              onValueChange={(v) => onFiltersChange({ ...filters, nodeId: v === "ALL" ? undefined : v })}
            >
              <SelectTrigger className="h-8 w-56 text-xs">
                <SelectValue placeholder="Node" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All nodes</SelectItem>
                {uniqueNodes.map((n) => (
                  <SelectItem key={n} value={n} className="font-mono text-xs">
                    {n}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          <Select
            value={filters.tenantId ?? "ALL"}
            onValueChange={(v) => onFiltersChange({ ...filters, tenantId: v === "ALL" ? undefined : v })}
          >
            <SelectTrigger className="h-8 w-44 text-xs">
              <SelectValue placeholder="Tenant" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All tenants</SelectItem>
              {uniqueTenants.map((t) => (
                <SelectItem key={t} value={t} className="font-mono text-xs">
                  {t}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select
            value={filters.lifecycle ?? "ALL"}
            onValueChange={(v) =>
              onFiltersChange({ ...filters, lifecycle: v as LifecycleState | "ALL" })
            }
          >
            <SelectTrigger className="h-8 w-40 text-xs">
              <SelectValue placeholder="Lifecycle" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All lifecycle</SelectItem>
              {LIFECYCLES.map((l) => (
                <SelectItem key={l} value={l} className="text-xs">
                  {l}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="text-xs text-muted-foreground ml-auto">
            {rows.length} loaded
          </span>
        </div>
      )}
      <div className="overflow-x-auto rounded border border-border bg-card">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 text-muted-foreground">
            <tr className="text-left">
              <th className="px-2 py-1.5 font-medium">Deployment</th>
              <th className="px-2 py-1.5 font-medium">Idx</th>
              <th className="px-2 py-1.5 font-medium">Module</th>
              <th className="px-2 py-1.5 font-medium">Node</th>
              <th className="px-2 py-1.5 font-medium">Lifecycle</th>
              <th className="px-2 py-1.5 font-medium">A/R</th>
              <th className="px-2 py-1.5 font-medium text-right">req/s</th>
              <th className="px-2 py-1.5 font-medium text-right">queue</th>
              <th className="px-2 py-1.5 font-medium text-right">cpu</th>
              <th className="px-2 py-1.5 font-medium text-right">mem</th>
              <th className="px-2 py-1.5 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.deploymentName}#${r.instanceIndex}`} className="border-t border-border hover:bg-muted/30">
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/deployments/$name"
                    params={{ name: r.deploymentName }}
                    className="text-primary hover:underline"
                  >
                    {r.deploymentName}
                  </Link>
                </td>
                <td className="px-2 py-1.5 font-mono">{r.instanceIndex}</td>
                <td className="px-2 py-1.5 font-mono text-muted-foreground">
                  {r.moduleId.name}@{r.moduleId.version}
                </td>
                <td className="px-2 py-1.5 font-mono">
                  <Link
                    to="/nodes/$nodeId"
                    params={{ nodeId: r.nodeId }}
                    className="text-primary hover:underline"
                  >
                    {r.nodeId}
                  </Link>
                </td>
                <td className="px-2 py-1.5">
                  <LifecycleBadge state={r.lifecycleState} />
                </td>
                <td className="px-2 py-1.5">
                  <div className="flex items-center gap-1">
                    <StatusDot variant={r.alive ? "ok" : "bad"} />
                    <StatusDot variant={r.ready ? "ok" : r.alive ? "warn" : "bad"} />
                  </div>
                </td>
                <td className="px-2 py-1.5 font-mono text-right">{r.requestRatePerSecond.toFixed(1)}</td>
                <td className="px-2 py-1.5 font-mono text-right">{r.queueDepth}</td>
                <td className="px-2 py-1.5 font-mono text-right">{fmtMillicores(r.cpuMillicoresUsed)}</td>
                <td className="px-2 py-1.5 font-mono text-right">{fmtBytes(r.memoryBytesUsed)}</td>
                <td className="px-2 py-1.5">
                  <Link
                    to="/instances/$name/$idx"
                    params={{ name: r.deploymentName, idx: String(r.instanceIndex) }}
                    className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1"
                  >
                    <FileText className="h-3 w-3" />
                    logs
                  </Link>
                </td>
              </tr>
            ))}
            {rows.length === 0 && !loading && (
              <tr>
                <td colSpan={11} className="px-4 py-8 text-center text-muted-foreground">
                  No instances match the current filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="flex justify-center">
        {hasMore ? (
          <Button variant="outline" size="sm" onClick={onLoadMore} disabled={loading}>
            {loading ? "Loading…" : "Load more"}
          </Button>
        ) : (
          <span className="text-xs text-muted-foreground">— end of list —</span>
        )}
      </div>
    </div>
  );
}
