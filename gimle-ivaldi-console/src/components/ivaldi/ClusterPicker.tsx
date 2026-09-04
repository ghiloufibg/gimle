import { Link } from "@tanstack/react-router";
import { Server } from "lucide-react";
import { useEffect } from "react";

import { cn } from "@/lib/utils";
import { useClustersStore } from "@/stores/useClustersStore";
import { useRunStore } from "@/stores/useRunStore";

/** Target-cluster selector shown wherever a blueprint can be run. */
export function ClusterPicker({ className }: { className?: string }) {
  const { clusters, selectedId, refresh, select } = useClustersStore();
  const cluster = useRunStore((s) => s.cluster);
  const setCluster = useRunStore((s) => s.setCluster);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    const next = clusters.find((c) => c.id === selectedId) ?? null;
    if (next?.id !== cluster?.id) setCluster(next);
  }, [clusters, selectedId, cluster?.id, setCluster]);

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
          value={selectedId ?? ""}
          onChange={(e) => select(e.target.value)}
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
