import { Link } from "@tanstack/react-router";
import { AlertTriangle, Boxes, Cpu, RefreshCw, Server, Users } from "lucide-react";

import { useOverviewStore } from "@/stores/useOverviewStore";
import { fmtRelativeTime, isStale } from "@/lib/format";
import { cn } from "@/lib/utils";

function MetricCard({
  label,
  value,
  icon,
  hint,
  alarm,
  to,
}: {
  label: string;
  value: React.ReactNode;
  icon: React.ReactNode;
  hint?: string;
  alarm?: boolean;
  to?: string;
}) {
  const body = (
    <div
      className={cn(
        "group relative h-full overflow-hidden rounded-lg border p-5 transition-all",
        alarm
          ? "border-status-bad/40 bg-status-bad-bg/30 hover:border-status-bad"
          : "border-border bg-card hover:border-primary/50 hover:shadow-[0_0_0_1px_color-mix(in_oklab,var(--primary)_25%,transparent)]",
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
        <span className={cn("opacity-60 transition-opacity group-hover:opacity-100", alarm && "text-status-bad")}>
          {icon}
        </span>
      </div>
      <div className={cn("mt-4 text-4xl font-semibold tabular-nums", alarm ? "text-status-bad" : "text-foreground")}>
        {value}
      </div>
      {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
      <span
        className={cn(
          "absolute inset-x-0 bottom-0 h-0.5 origin-left scale-x-0 transition-transform duration-300 group-hover:scale-x-100",
          alarm ? "bg-status-bad" : "bg-primary",
        )}
      />
    </div>
  );
  return to ? (
    <Link to={to} className="block h-full">
      {body}
    </Link>
  ) : (
    body
  );
}

export function OverviewSignal() {
  const s = useOverviewStore();

  return (
    <div className="p-4 md:p-8 max-w-[1600px] mx-auto">
      <div className="mb-8 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl md:text-4xl font-semibold tracking-tight">Cluster overview</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {s.loading ? "Syncing control plane…" : "Control plane operational — all signals nominal."}
          </p>
        </div>
        <button
          onClick={() => s.load()}
          className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-3 py-2 text-sm font-medium transition-colors hover:border-primary/50 hover:text-primary"
        >
          <RefreshCw className={cn("h-4 w-4", s.loading && "animate-spin")} />
          Refresh
        </button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4">
        <MetricCard
          label="Nodes"
          value={s.nodesTotal}
          icon={<Server className="h-5 w-5" />}
          hint={s.nodesStale > 0 ? `${s.nodesStale} stale heartbeat(s)` : "All heartbeats fresh"}
          alarm={s.nodesStale > 0}
        />
        <MetricCard
          label="Deployments"
          value={s.deploymentsTotal}
          icon={<Boxes className="h-5 w-5" />}
          hint="Across all tenants"
          to="/deployments"
        />
        <MetricCard
          label="Tenants"
          value={s.tenantsTotal}
          icon={<Users className="h-5 w-5" />}
          hint="Registered namespaces"
          to="/tenants"
        />
        <MetricCard
          label="Unplaced instances"
          value={s.unplacedInstances}
          icon={<Cpu className="h-5 w-5" />}
          hint={s.unplacedInstances > 0 ? "Awaiting placement" : "Everything scheduled"}
          alarm={s.unplacedInstances > 0}
        />
        <MetricCard
          label="Quota alarms"
          value={s.quotaViolating}
          icon={<AlertTriangle className="h-5 w-5" />}
          hint={s.quotaViolating > 0 ? "Deployments over quota" : "Within limits"}
          alarm={s.quotaViolating > 0}
        />
      </div>

      <div className="mt-8 grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section className="rounded-lg border border-border bg-card">
          <header className="flex items-center justify-between border-b border-border px-5 py-3">
            <h2 className="text-sm font-semibold">Recent deployments</h2>
            <Link to="/deployments" className="text-xs text-primary hover:underline">
              View all
            </Link>
          </header>
          <ul className="divide-y divide-border">
            {s.recentDeployments.map((d) => {
              const ready = d.instances.length;
              const desired = d.spec.replicas;
              const pct = Math.min(100, Math.round((ready / Math.max(1, desired)) * 100));
              const full = ready >= desired;
              return (
                <li key={d.spec.name} className="px-5 py-4 transition-colors hover:bg-accent/40">
                  <div className="flex items-center justify-between gap-3">
                    <Link
                      to="/deployments/$name"
                      params={{ name: d.spec.name }}
                      className="truncate text-sm font-medium hover:text-primary hover:underline"
                    >
                      {d.spec.name}
                    </Link>
                    <span
                      className={cn(
                        "shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium",
                        full ? "bg-status-ok-bg text-status-ok" : "bg-status-warn-bg text-status-warn",
                      )}
                    >
                      {ready}/{desired} {full ? "ready" : "rolling"}
                    </span>
                  </div>
                  <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className={cn("h-full rounded-full transition-all", full ? "bg-status-ok" : "bg-status-warn")}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                </li>
              );
            })}
            {s.loading && s.recentDeployments.length === 0 && (
              <li className="px-5 py-6 text-sm text-muted-foreground">Loading…</li>
            )}
          </ul>
        </section>

        <section className="rounded-lg border border-border bg-card">
          <header className="flex items-center justify-between border-b border-border px-5 py-3">
            <h2 className="text-sm font-semibold">Node registry</h2>
            <Link to="/nodes" className="text-xs text-primary hover:underline">
              View all
            </Link>
          </header>
          <ul className="divide-y divide-border">
            {s.recentNodes.map((n) => {
              const stale = isStale(n.lastHeartbeatAt);
              const load = Math.min(
                100,
                Math.round(
                  (n.capacity
                    ? n.capacity.assignedCpuMillicores / Math.max(1, n.capacity.totalCpuMillicores)
                    : 0) * 100,
                ),
              );
              return (
                <li key={n.nodeId} className="flex items-center gap-4 px-5 py-4 transition-colors hover:bg-accent/40">
                  <span
                    className={cn(
                      "h-2 w-2 shrink-0 rounded-full",
                      stale ? "bg-status-bad" : "bg-status-ok animate-pulse",
                    )}
                  />
                  <div className="min-w-0 flex-1">
                    <Link
                      to="/nodes/$nodeId"
                      params={{ nodeId: n.nodeId }}
                      className="block truncate text-sm font-medium hover:text-primary hover:underline"
                    >
                      {n.nodeId}
                    </Link>
                    <p className="text-xs text-muted-foreground">
                      Heartbeat {fmtRelativeTime(n.lastHeartbeatAt)}
                    </p>
                  </div>
                  <div className="w-28 shrink-0">
                    <div className="flex items-center justify-between text-[11px] text-muted-foreground">
                      <span>CPU</span>
                      <span className="tabular-nums">{stale ? "—" : `${load}%`}</span>
                    </div>
                    <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
                      <div
                        className={cn("h-full rounded-full", stale ? "bg-status-bad/50" : "bg-primary")}
                        style={{ width: `${stale ? 100 : load}%` }}
                      />
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      </div>
    </div>
  );
}
