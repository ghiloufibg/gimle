import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useOverviewStore } from "@/stores/useOverviewStore";
import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { fmtRelativeTime, isStale } from "@/lib/format";
import { isDeploymentHealthy } from "@/lib/deployment-health";
import { useDisplayStore } from "@/stores/useDisplayStore";
import { OverviewSignal } from "@/components/overview-signal";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Overview — Gimlé Console" },
      {
        name: "description",
        content: "Cluster overview: node, deployment, tenant, and health totals.",
      },
      { property: "og:title", content: "Overview — Gimlé Console" },
      { property: "og:description", content: "Cluster overview at a glance." },
    ],
  }),
  component: Overview,
});

function Overview() {
  const s = useOverviewStore();
  const mode = useDisplayStore((d) => d.mode);

  useEffect(() => {
    s.load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (mode === "signal") return <OverviewSignal />;

  return (
    <PageContainer>
      <PageHeader
        title="Cluster Overview"
        eyebrow="Gimlé // Console"
        actions={
          <div className="flex flex-col items-end">
            <span className="hud-label">Control plane</span>
            <span className="text-sm text-primary font-mono">
              {s.loading ? "syncing…" : "operational"}
            </span>
          </div>
        }
      />

      {/* Stats row */}
      <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-5 gap-4">
        <StatTile
          label="Total nodes"
          value={s.nodesTotal}
          tone="primary"
          note={
            s.nodesStale > 0 ? (
              <span className="text-xs font-mono font-bold text-status-bad">
                {s.nodesStale} STALE
              </span>
            ) : (
              <span className="text-[10px] uppercase tracking-tighter text-muted-foreground">
                all fresh
              </span>
            )
          }
        />
        <StatTile
          label="Active deployments"
          value={s.deploymentsTotal}
          note={
            <Link
              to="/deployments"
              className="text-[10px] uppercase tracking-tighter text-primary hover:underline"
            >
              inspect
            </Link>
          }
        />
        <StatTile
          label="Total tenants"
          value={s.tenantsTotal}
          note={
            <Link
              to="/tenants"
              className="text-[10px] uppercase tracking-tighter text-primary hover:underline"
            >
              inspect
            </Link>
          }
        />
        <StatTile
          label="Unplaced"
          value={s.unplacedInstances}
          tone={s.unplacedInstances > 0 ? "alarm" : "muted"}
          note={
            <span className="text-[10px] uppercase tracking-tighter text-muted-foreground">
              {s.unplacedInstances > 0 ? "pending placement" : "clear"}
            </span>
          }
        />
        <StatTile
          label="Quota alarms"
          value={s.quotaViolating}
          tone={s.quotaViolating > 0 ? "alarm" : "muted"}
          note={
            s.quotaViolating > 0 ? (
              <span className="text-[10px] uppercase tracking-tighter text-status-bad animate-pulse">
                action req
              </span>
            ) : (
              <span className="text-[10px] uppercase tracking-tighter text-muted-foreground">
                clear
              </span>
            )
          }
        />
      </div>

      {/* Main control grid */}
      <div className="mt-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
        <Panel
          title="Recent deployments"
          className="lg:col-span-7"
          aside={
            <Link
              to="/deployments"
              className="text-[10px] uppercase tracking-widest text-muted-foreground hover:text-primary"
            >
              view all
            </Link>
          }
        >
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead className="text-[10px] uppercase tracking-wider text-muted-foreground border-b border-primary/5">
                <tr>
                  <th className="px-4 py-3 font-semibold">Deployment identifier</th>
                  <th className="px-4 py-3 font-semibold text-center">State</th>
                  <th className="px-4 py-3 font-semibold text-right">Capacity</th>
                </tr>
              </thead>
              <tbody className="text-sm divide-y divide-primary/5">
                {s.recentDeployments.map((d) => {
                  const ready = d.instances.length;
                  const desired = d.spec.replicas;
                  const full = isDeploymentHealthy(d);
                  return (
                    <tr key={d.spec.name} className="hover:bg-primary/5 transition-colors">
                      <td className="px-4 py-3 font-mono">
                        <Link
                          to="/deployments/$name"
                          params={{ name: d.spec.name }}
                          className="text-signal hover:text-primary hover:underline"
                        >
                          {d.spec.name}
                        </Link>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-center gap-1.5">
                          <span
                            className={`h-1.5 w-1.5 rounded-full ${full ? "bg-status-ok" : "bg-status-warn"}`}
                          />
                          <span
                            className={`text-[10px] uppercase tracking-tighter ${full ? "text-status-ok" : "text-status-warn"}`}
                          >
                            {full ? "synced" : "rolling"}
                          </span>
                        </div>
                      </td>
                      <td
                        className={`px-4 py-3 text-right font-mono font-bold ${full ? "text-status-ok" : "text-status-warn"}`}
                      >
                        {String(ready).padStart(2, "0")}/{String(desired).padStart(2, "0")}
                      </td>
                    </tr>
                  );
                })}
                {s.loading && s.recentDeployments.length === 0 && (
                  <tr>
                    <td colSpan={3} className="px-4 py-6 text-xs text-muted-foreground font-mono">
                      loading…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel
          title="Node registry"
          className="lg:col-span-5"
          aside={
            <button
              onClick={() => s.load()}
              className="text-[10px] bg-primary/10 hover:bg-primary/20 text-primary px-2 py-0.5 rounded border border-primary/20 transition-colors uppercase tracking-widest"
            >
              Refresh map
            </button>
          }
        >
          <div className="divide-y divide-primary/5">
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
                <div
                  key={n.nodeId}
                  className={`p-4 flex items-center justify-between gap-3 ${stale ? "bg-status-bad-bg/30" : "hover:bg-primary/5"} transition-colors`}
                >
                  <div className="flex flex-col min-w-0">
                    <Link
                      to="/nodes/$nodeId"
                      params={{ nodeId: n.nodeId }}
                      className={`text-xs font-bold font-mono truncate hover:underline ${stale ? "text-status-bad" : "text-signal"}`}
                    >
                      {n.nodeId}
                    </Link>
                    <span
                      className={`text-[10px] uppercase tracking-wider ${stale ? "text-status-bad/70" : "text-muted-foreground"}`}
                    >
                      Heartbeat: {fmtRelativeTime(n.lastHeartbeatAt)}
                    </span>
                  </div>
                  <div className="text-right shrink-0">
                    <div
                      className={`text-[10px] font-bold uppercase tracking-widest ${stale ? "text-status-bad" : "text-status-ok"}`}
                    >
                      {stale ? "Stale" : "Active"}
                    </div>
                    <div className="w-24 h-1 bg-muted rounded-full mt-1 overflow-hidden">
                      <div
                        className={`h-full ${stale ? "bg-status-bad/50" : "bg-primary"}`}
                        style={{ width: `${stale ? 100 : load}%` }}
                      />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Panel>
      </div>

      {/* Footer context bar */}
      <div className="mt-6 flex flex-wrap items-center justify-between gap-3 pt-4 border-t border-primary/10 text-[10px] uppercase tracking-[0.2em] font-mono text-muted-foreground">
        <div className="flex gap-6">
          <span>Ver: {__APP_VERSION__}</span>
        </div>
        <div className="flex gap-4">
          <span>Nodes stale: {s.nodesStale}</span>
          <span>Unplaced: {s.unplacedInstances}</span>
        </div>
      </div>
    </PageContainer>
  );
}
