import { Link } from "@tanstack/react-router";
import { Server } from "lucide-react";
import { useEffect } from "react";

import { cn } from "@/lib/utils";
import { useClustersStore } from "@/stores/useClustersStore";
import { useRunStore } from "@/stores/useRunStore";

/**
 * Target-cluster selector shown wherever a blueprint can be run.
 *
 * <p>When it is given a blueprint, the choice belongs to that blueprint: picking a cluster on one
 * blueprint's runner no longer silently repoints every other one at it.
 */
export function ClusterPicker({
  blueprintId,
  className,
}: {
  blueprintId?: string;
  className?: string;
}) {
  const { clusters, selectedId, refresh, select, selectedFor, selectFor } = useClustersStore();
  const cluster = useRunStore((s) => s.cluster);
  const setCluster = useRunStore((s) => s.setCluster);
  const target = blueprintId ? selectedFor(blueprintId) : null;
  const currentId = (blueprintId ? target?.id : selectedId) ?? selectedId;

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    const next = clusters.find((c) => c.id === currentId) ?? null;
    if (next?.id !== cluster?.id) setCluster(next);
  }, [clusters, currentId, cluster?.id, setCluster]);

  return (
    <div className={cn("space-y-1", className)}>
      <div className="hud-label flex items-center justify-between">
        <span className="inline-flex items-center gap-1">
          <Server className="size-3" /> Target cluster
        </span>
        <Link to="/clusters" className="text-primary hover:underline">
          Manage
        </Link>
      </div>
      {clusters.length === 0 ? (
        <p className="text-[10px] text-status-warn">
          No cluster configured.{" "}
          <Link to="/clusters" className="text-primary hover:underline">
            Add one
          </Link>
          .
        </p>
      ) : (
        <select
          value={currentId ?? ""}
          onChange={(e) =>
            blueprintId ? selectFor(blueprintId, e.target.value) : select(e.target.value)
          }
          className="h-7 w-full rounded-sm border border-border bg-card px-2 font-mono text-[11px] text-foreground"
        >
          {clusters.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} — {c.environment} — {c.controlPlaneUrl}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}
