import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useOverviewStore } from "@/stores/useOverviewStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { StatusDot } from "@/components/status";
import { fmtRelativeTime, isStale } from "@/lib/format";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Overview — Gimlé Console" },
      { name: "description", content: "Cluster overview: node, deployment, tenant, and health totals." },
      { property: "og:title", content: "Overview — Gimlé Console" },
      { property: "og:description", content: "Cluster overview at a glance." },
    ],
  }),
  component: Overview,
});

function Card({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: React.ReactNode;
  hint?: React.ReactNode;
  tone?: "ok" | "warn" | "bad" | "info";
}) {
  return (
    <div className="rounded border border-border bg-card p-4">
      <div className="flex items-center gap-2 text-[10px] uppercase tracking-wider text-muted-foreground">
        {tone && <StatusDot variant={tone} />}
        {label}
      </div>
      <div className="mt-2 text-3xl font-semibold font-mono tabular-nums">{value}</div>
      {hint && <div className="mt-1 text-xs text-muted-foreground">{hint}</div>}
    </div>
  );
}

function Overview() {
  const s = useOverviewStore();

  useEffect(() => {
    s.load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <PageContainer>
      <PageHeader
        title="Cluster overview"
        subtitle="Aggregate health across the full cluster."
      />
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
        <Card
          label="Nodes"
          value={s.nodesTotal}
          hint={
            <span>
              <Link to="/nodes" className="text-primary hover:underline">
                view nodes
              </Link>
              {s.nodesStale > 0 && <> · {s.nodesStale} stale</>}
            </span>
          }
          tone={s.nodesStale > 0 ? "warn" : "ok"}
        />
        <Card
          label="Deployments"
          value={s.deploymentsTotal}
          hint={
            <Link to="/deployments" className="text-primary hover:underline">
              view deployments
            </Link>
          }
          tone="info"
        />
        <Card
          label="Tenants"
          value={s.tenantsTotal}
          hint={
            <Link to="/tenants" className="text-primary hover:underline">
              view tenants
            </Link>
          }
          tone="info"
        />
        <Card
          label="Unplaced instances"
          value={s.unplacedInstances}
          hint="Sum across deployments"
          tone={s.unplacedInstances > 0 ? "bad" : "ok"}
        />
        <Card
          label="Quota-violating"
          value={s.quotaViolating}
          hint="Deployments over their tenant quota"
          tone={s.quotaViolating > 0 ? "bad" : "ok"}
        />
      </div>

      <div className="mt-8 grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="rounded border border-border bg-card">
          <div className="px-3 py-2 border-b border-border text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Recent deployments
          </div>
          <ul className="divide-y divide-border">
            {s.recentDeployments.map((d) => (
              <li key={d.spec.name} className="px-3 py-2 flex items-center justify-between text-xs">
                <Link
                  to="/deployments/$name"
                  params={{ name: d.spec.name }}
                  className="font-mono text-primary hover:underline truncate mr-2"
                >
                  {d.spec.name}
                </Link>
                <span className="text-muted-foreground font-mono">
                  {d.instances.length}/{d.spec.replicas} replicas
                </span>
              </li>
            ))}
            {s.loading && s.recentDeployments.length === 0 && (
              <li className="px-3 py-4 text-xs text-muted-foreground">Loading…</li>
            )}
          </ul>
        </div>
        <div className="rounded border border-border bg-card">
          <div className="px-3 py-2 border-b border-border text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Nodes
          </div>
          <ul className="divide-y divide-border">
            {s.recentNodes.map((n) => {
              const stale = isStale(n.lastHeartbeatAt);
              return (
                <li key={n.nodeId} className="px-3 py-2 flex items-center justify-between text-xs">
                  <div className="flex items-center gap-2 min-w-0">
                    <StatusDot variant={stale ? "warn" : "ok"} />
                    <Link
                      to="/nodes/$nodeId"
                      params={{ nodeId: n.nodeId }}
                      className="font-mono text-primary hover:underline truncate"
                    >
                      {n.nodeId}
                    </Link>
                  </div>
                  <span className="text-muted-foreground font-mono">
                    {fmtRelativeTime(n.lastHeartbeatAt)}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </PageContainer>
  );
}
