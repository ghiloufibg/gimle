import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";
import {
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
  ZAxis,
} from "recharts";

import { AXIS, ChartTooltip } from "@/components/chart-kit";
import { MetricsHistoryPanel } from "@/components/metrics-history-panel";
import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { ProcessPicker, defaultProcessTarget } from "@/components/process-picker";
import { fmtBytes, fmtMillicores, isStale } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useOverviewStore } from "@/stores/useOverviewStore";
import { useTenantsStore } from "@/stores/useTenantsStore";
import type { LifecycleState, ProcessTarget } from "@/types";

export const Route = createFileRoute("/metrics")({
  head: () => ({
    meta: [
      { title: "Metrics — Gimlé Console" },
      {
        name: "description",
        content:
          "Cluster metrics: lifecycle mix, placement coverage, node capacity balance, tenant quota pressure and instance backpressure.",
      },
      { property: "og:title", content: "Metrics — Gimlé Console" },
      {
        property: "og:description",
        content: "Charted view of lifecycle, placement, capacity, quota and workload signals.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Metrics,
});

const LIFECYCLE_ORDER: LifecycleState[] = [
  "ACTIVE",
  "STARTING",
  "RESOLVED",
  "STOPPING",
  "INSTALLED",
  "UNINSTALLED",
];

const LIFECYCLE_COLOR: Record<LifecycleState, string> = {
  ACTIVE: "var(--status-ok)",
  STARTING: "var(--primary)",
  RESOLVED: "var(--signal)",
  STOPPING: "var(--status-warn)",
  INSTALLED: "var(--status-muted)",
  UNINSTALLED: "var(--status-bad)",
};

/** Horizontal used-vs-allowance bullet row. */
function Bullet({
  label,
  used,
  max,
  display,
}: {
  label: string;
  used: number;
  max: number;
  display: string;
}) {
  const pct = Math.round((used / Math.max(1, max)) * 100);
  const over = pct > 100;
  return (
    <div className="mb-1.5 last:mb-0">
      <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        <span>{label}</span>
        <span className={cn("tabular-nums", over ? "text-status-bad" : "text-foreground")}>
          {display} · {pct}%
        </span>
      </div>
      <div className="relative mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn(
            "h-full",
            over ? "bg-status-bad" : pct >= 80 ? "bg-status-warn" : "bg-primary",
          )}
          style={{ width: `${Math.min(100, pct)}%` }}
        />
        <span className="absolute inset-y-0 left-[80%] w-px bg-foreground/25" />
      </div>
    </div>
  );
}

function Metrics() {
  const s = useOverviewStore();
  const [historyTarget, setHistoryTarget] = useState<ProcessTarget>(defaultProcessTarget);
  const tenants = useTenantsStore((t) => t.items);
  const loadTenants = useTenantsStore((t) => t.loadFirstPage);

  useEffect(() => {
    if (!s.loaded) s.load();
    if (!s.allLoaded) s.loadAll();
    if (tenants.length === 0) loadTenants();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const instances = useMemo(
    () => s.allDeployments.flatMap((d) => d.instances.map((i) => ({ d, i }))),
    [s.allDeployments],
  );

  /** 1 — lifecycle mix */
  const lifecycle = useMemo(() => {
    const counts = new Map<LifecycleState, number>();
    for (const { i } of instances) {
      const k = i.observation.lifecycleState;
      counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    return LIFECYCLE_ORDER.filter((k) => counts.get(k)).map((k) => ({
      name: k,
      value: counts.get(k)!,
      __label: k,
      __tip: [["instances", String(counts.get(k))]] as Array<[string, string]>,
    }));
  }, [instances]);

  const readyCount = instances.filter(({ i }) => i.observation.ready).length;

  /** 2 — placement coverage */
  const placement = useMemo(() => {
    const desired = s.allDeployments.reduce((a, d) => a + d.spec.replicas, 0);
    const placed = s.allDeployments.reduce((a, d) => a + d.instances.length, 0);
    const unplaced = s.allDeployments.reduce((a, d) => a + d.unplacedCount, 0);
    return { desired, placed, unplaced, pct: Math.round((placed / Math.max(1, desired)) * 100) };
  }, [s.allDeployments]);

  /** 3 — heartbeat freshness barcode + 4 — capacity scatter */
  const nodePoints = useMemo(
    () =>
      s.allNodes.map((n) => {
        const cpu = Math.round(
          (n.capacity.assignedCpuMillicores / Math.max(1, n.capacity.totalCpuMillicores)) * 100,
        );
        const mem = Math.round(
          (n.capacity.assignedMemoryBytes / Math.max(1, n.capacity.totalMemoryBytes)) * 100,
        );
        const count = instances.filter(({ i }) => i.nodeId === n.nodeId).length;
        const stale = isStale(n.lastHeartbeatAt);
        return {
          cpu,
          mem,
          count: Math.max(1, count),
          stale,
          nodeId: n.nodeId,
          __label: n.nodeId,
          __tip: [
            ["cpu", `${cpu}%`],
            ["memory", `${mem}%`],
            ["instances", String(count)],
            ["heartbeat", stale ? "stale" : "fresh"],
          ] as Array<[string, string]>,
        };
      }),
    [s.allNodes, instances],
  );

  /** 5 — tenant quota pressure */
  const tenantRows = useMemo(
    () =>
      tenants.map((t) => {
        const ds = s.allDeployments.filter((d) => d.spec.tenantId === t.id);
        const insts = ds.flatMap((d) => d.instances);
        const cpu = insts.reduce((a, i) => a + i.observation.cpuMillicoresUsed, 0);
        const mem = insts.reduce((a, i) => a + i.observation.memoryBytesUsed, 0);
        return { t, cpu, mem, count: insts.length, violating: ds.some((d) => d.quotaViolating) };
      }),
    [tenants, s.allDeployments],
  );

  /** 6 — backpressure scatter */
  const loadPoints = useMemo(
    () =>
      instances.map(({ d, i }) => ({
        rate: Math.round(i.observation.requestRatePerSecond),
        queue: i.observation.queueDepth,
        state: i.observation.lifecycleState,
        __label: `${d.spec.name} #${i.instanceIndex}`,
        __tip: [
          ["node", i.nodeId],
          ["req/s", i.observation.requestRatePerSecond.toFixed(1)],
          ["queue", String(i.observation.queueDepth)],
          ["state", i.observation.lifecycleState],
        ] as Array<[string, string]>,
      })),
    [instances],
  );

  const busiest = useMemo(
    () =>
      [...instances]
        .sort((a, b) => b.i.observation.cpuMillicoresUsed - a.i.observation.cpuMillicoresUsed)
        .slice(0, 8),
    [instances],
  );
  const busiestMax = busiest[0]?.i.observation.cpuMillicoresUsed ?? 1;

  return (
    <PageContainer>
      <PageHeader
        title="Cluster Metrics"
        eyebrow="Gimlé // Metrics"
        subtitle="Charted signals derived from the same control-plane data as the overview and topology views."
        actions={
          <button
            onClick={() => {
              s.load();
              s.loadAll();
            }}
            className="rounded-sm border border-primary/20 bg-primary/10 px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-primary transition-colors hover:bg-primary/20"
          >
            {s.loading || s.allLoading ? "syncing…" : "Refresh metrics"}
          </button>
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-px bg-primary/10 lg:grid-cols-4">
        <StatTile
          label="Instances observed"
          value={instances.length}
          note={`${readyCount} ready`}
          tone="primary"
        />
        <StatTile
          label="Placement coverage"
          value={`${placement.pct}%`}
          note={`${placement.placed}/${placement.desired} replicas`}
          tone={placement.unplaced > 0 ? "alarm" : "default"}
        />
        <StatTile
          label="Stale nodes"
          value={s.nodesStale}
          note={`${s.nodesTotal} registered`}
          tone={s.nodesStale > 0 ? "alarm" : "default"}
        />
        <StatTile
          label="Quota alarms"
          value={s.quotaViolating}
          note={`${tenants.length} tenants`}
          tone={s.quotaViolating > 0 ? "alarm" : "default"}
        />
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <Panel
          title="Lifecycle mix"
          aside={
            <span className="hud-label text-muted-foreground">{instances.length} instances</span>
          }
        >
          <div className="grid grid-cols-1 gap-2 p-3 sm:grid-cols-[1fr_auto]">
            <div className="h-56">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={lifecycle}
                    dataKey="value"
                    innerRadius="58%"
                    outerRadius="85%"
                    paddingAngle={2}
                    stroke="var(--background)"
                  >
                    {lifecycle.map((e) => (
                      <Cell key={e.name} fill={LIFECYCLE_COLOR[e.name as LifecycleState]} />
                    ))}
                  </Pie>
                  <Tooltip content={<ChartTooltip />} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <ul className="self-center space-y-1 pr-2">
              {lifecycle.map((e) => (
                <li
                  key={e.name}
                  className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-widest"
                >
                  <span
                    className="h-2.5 w-2.5 rounded-[1px]"
                    style={{ background: LIFECYCLE_COLOR[e.name as LifecycleState] }}
                  />
                  <span className="text-muted-foreground">{e.name}</span>
                  <span className="ml-auto tabular-nums text-signal">{e.value}</span>
                </li>
              ))}
              {lifecycle.length === 0 && (
                <li className="font-mono text-[10px] text-muted-foreground">no instance data</li>
              )}
            </ul>
          </div>
        </Panel>

        <Panel
          title="Placement coverage & heartbeat"
          aside={
            <span
              className={cn(
                "hud-label",
                placement.unplaced > 0 ? "text-status-bad" : "text-muted-foreground",
              )}
            >
              {placement.unplaced} unplaced
            </span>
          }
        >
          <div className="space-y-4 p-4">
            <div>
              <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                <span>placed vs desired replicas</span>
                <span className="tabular-nums text-signal">
                  {placement.placed}/{placement.desired}
                </span>
              </div>
              <div className="mt-1.5 flex h-3 overflow-hidden rounded-sm bg-muted">
                <div className="bg-status-ok" style={{ width: `${placement.pct}%` }} />
                <div className="flex-1 bg-status-bad/40" />
              </div>
            </div>

            <div>
              <div className="hud-label mb-1.5 text-muted-foreground">heartbeat freshness</div>
              <div className="flex flex-wrap gap-1">
                {s.allNodes.map((n) => {
                  const never = n.lastHeartbeatAt === null;
                  const stale = isStale(n.lastHeartbeatAt);
                  return (
                    <span
                      key={n.nodeId}
                      title={`${n.nodeId} — ${never ? "never" : stale ? "stale" : "fresh"}`}
                      className={cn(
                        "h-6 w-3 rounded-[1px]",
                        never ? "bg-status-bad" : stale ? "bg-status-warn" : "bg-status-ok/80",
                      )}
                    />
                  );
                })}
                {s.allNodes.length === 0 && (
                  <span className="font-mono text-[10px] text-muted-foreground">no nodes</span>
                )}
              </div>
            </div>

            <div>
              <div className="hud-label mb-1.5 text-muted-foreground">top cpu consumers</div>
              <ul className="space-y-1">
                {busiest.map(({ d, i }) => (
                  <li key={`${d.spec.name}-${i.instanceIndex}`} className="flex items-center gap-2">
                    <span className="w-40 shrink-0 truncate font-mono text-[10px] text-signal">
                      {d.spec.name} #{i.instanceIndex}
                    </span>
                    <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                      <span
                        className="block h-full bg-primary"
                        style={{
                          width: `${Math.round((i.observation.cpuMillicoresUsed / Math.max(1, busiestMax)) * 100)}%`,
                        }}
                      />
                    </span>
                    <span className="w-16 shrink-0 text-right font-mono text-[10px] tabular-nums text-muted-foreground">
                      {fmtMillicores(i.observation.cpuMillicoresUsed)}
                    </span>
                  </li>
                ))}
                {busiest.length === 0 && (
                  <li className="font-mono text-[10px] text-muted-foreground">no instance data</li>
                )}
              </ul>
            </div>
          </div>
        </Panel>

        <Panel
          title="Node capacity balance"
          aside={<span className="hud-label text-muted-foreground">cpu % × memory %</span>}
        >
          <div className="h-64 p-3">
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 8, right: 12, bottom: 16, left: 0 }}>
                <XAxis
                  type="number"
                  dataKey="cpu"
                  name="cpu"
                  domain={[0, 100]}
                  tick={AXIS}
                  tickLine={false}
                  axisLine={{ stroke: "var(--status-muted)" }}
                  label={{
                    value: "cpu %",
                    position: "insideBottom",
                    offset: -8,
                    fill: "var(--status-muted)",
                    fontSize: 10,
                  }}
                />
                <YAxis
                  type="number"
                  dataKey="mem"
                  name="memory"
                  domain={[0, 100]}
                  tick={AXIS}
                  tickLine={false}
                  axisLine={{ stroke: "var(--status-muted)" }}
                  width={34}
                />
                <ZAxis type="number" dataKey="count" range={[40, 340]} />
                <Tooltip
                  content={<ChartTooltip />}
                  cursor={{ stroke: "var(--primary)", strokeOpacity: 0.3 }}
                />
                <Scatter data={nodePoints}>
                  {nodePoints.map((p) => (
                    <Cell
                      key={p.nodeId}
                      fill={
                        p.stale
                          ? "var(--status-bad)"
                          : p.cpu >= 80 || p.mem >= 80
                            ? "var(--status-warn)"
                            : "var(--primary)"
                      }
                      fillOpacity={0.55}
                      stroke={p.stale ? "var(--status-bad)" : "var(--primary)"}
                    />
                  ))}
                </Scatter>
              </ScatterChart>
            </ResponsiveContainer>
          </div>
        </Panel>

        <Panel
          title="Backpressure — request rate vs queue depth"
          aside={
            <span className="hud-label text-muted-foreground">{loadPoints.length} instances</span>
          }
        >
          <div className="h-64 p-3">
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 8, right: 12, bottom: 16, left: 0 }}>
                <XAxis
                  type="number"
                  dataKey="rate"
                  name="req/s"
                  tick={AXIS}
                  tickLine={false}
                  axisLine={{ stroke: "var(--status-muted)" }}
                  label={{
                    value: "req/s",
                    position: "insideBottom",
                    offset: -8,
                    fill: "var(--status-muted)",
                    fontSize: 10,
                  }}
                />
                <YAxis
                  type="number"
                  dataKey="queue"
                  name="queue"
                  tick={AXIS}
                  tickLine={false}
                  axisLine={{ stroke: "var(--status-muted)" }}
                  width={34}
                />
                <Tooltip
                  content={<ChartTooltip />}
                  cursor={{ stroke: "var(--primary)", strokeOpacity: 0.3 }}
                />
                <Scatter data={loadPoints}>
                  {loadPoints.map((p, i) => (
                    <Cell
                      key={i}
                      fill={LIFECYCLE_COLOR[p.state]}
                      fillOpacity={0.6}
                      stroke={LIFECYCLE_COLOR[p.state]}
                    />
                  ))}
                </Scatter>
              </ScatterChart>
            </ResponsiveContainer>
          </div>
        </Panel>

        <Panel
          title="Tenant quota pressure"
          aside={<span className="hud-label text-muted-foreground">used vs allowance</span>}
          className="xl:col-span-2"
        >
          <div className="grid grid-cols-1 gap-px bg-primary/5 md:grid-cols-2 xl:grid-cols-3">
            {tenantRows.map(({ t, cpu, mem, count, violating }) => (
              <div
                key={t.id}
                className={cn(
                  "border-l-2 bg-primary/[0.04] p-3",
                  violating ? "border-status-bad" : "border-primary/40",
                )}
              >
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="truncate font-mono text-xs font-bold text-signal">{t.id}</span>
                  <span
                    className={cn(
                      "shrink-0 font-mono text-[10px] uppercase tracking-widest",
                      violating ? "text-status-bad" : "text-muted-foreground",
                    )}
                  >
                    {violating ? "over quota" : "within quota"}
                  </span>
                </div>
                <Bullet
                  label="cpu"
                  used={cpu}
                  max={t.quota.maxCpuMillicores}
                  display={`${fmtMillicores(cpu)} / ${fmtMillicores(t.quota.maxCpuMillicores)}`}
                />
                <Bullet
                  label="memory"
                  used={mem}
                  max={t.quota.maxMemoryBytes}
                  display={`${fmtBytes(mem)} / ${fmtBytes(t.quota.maxMemoryBytes)}`}
                />
                <Bullet
                  label="instances"
                  used={count}
                  max={t.quota.maxInstances}
                  display={`${count} / ${t.quota.maxInstances}`}
                />
              </div>
            ))}
            {tenantRows.length === 0 && (
              <p className="p-3 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                no tenant data
              </p>
            )}
          </div>
        </Panel>
      </div>

      <section className="mt-6">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3 border-b border-primary/20 pb-3">
          <div>
            <div className="hud-label mb-1">Gimlé // Metrics history</div>
            <h2 className="text-lg font-light tracking-tight text-foreground">
              Process time series
            </h2>
          </div>
          <ProcessPicker value={historyTarget} onChange={setHistoryTarget} />
        </div>
        <MetricsHistoryPanel
          key={`${historyTarget.processKind}:${historyTarget.processId}`}
          target={historyTarget}
        />
      </section>

      {s.allLoading && instances.length === 0 && (
        <p className="mt-4 font-mono text-xs text-muted-foreground">loading metrics…</p>
      )}
    </PageContainer>
  );
}
