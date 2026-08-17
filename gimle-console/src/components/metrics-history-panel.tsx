import { useEffect, useMemo } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { AXIS, ChartTooltip } from "@/components/chart-kit";
import { Panel } from "@/components/page-shell";
import { fmtBytes } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useMetricsHistoryStore } from "@/stores/useMetricsHistoryStore";
import type { MetricsHistoryLine, ProcessTarget } from "@/types";

const MEASUREMENT_PREFERENCE = ["VALUE", "COUNT", "MAX", "TOTAL_TIME"];

function primaryKey(line: MetricsHistoryLine): string | null {
  for (const k of MEASUREMENT_PREFERENCE) if (k in line.measurements) return k;
  return Object.keys(line.measurements)[0] ?? null;
}

function clock(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}:${String(
    d.getSeconds(),
  ).padStart(2, "0")}`;
}

function formatValue(name: string, key: string, v: number): string {
  if (name.includes("memory") && key === "VALUE") return fmtBytes(v);
  if (Math.abs(v) < 1) return v.toFixed(4);
  return v.toLocaleString(undefined, { maximumFractionDigits: 2 });
}

interface Series {
  name: string;
  type: string;
  key: string;
  points: Array<Record<string, unknown>>;
  hasPercentiles: boolean;
}

/** Time-series charts for whichever metric names are present in the fetched window -- one per
 * meter, each with its own y-axis (a "http.server.requests" TIMER and a "jvm.memory.used" GAUGE
 * are not on the same scale). */
export function MetricsHistoryPanel({ target }: { target: ProcessTarget }) {
  const store = useMetricsHistoryStore(target);
  const lines = store((s) => s.lines);
  const loading = store((s) => s.loading);
  const error = store((s) => s.error);
  const live = store((s) => s.live);
  const loadFirstPage = store((s) => s.loadFirstPage);
  const loadOlder = store((s) => s.loadOlder);
  const startLive = store((s) => s.startLive);
  const stopLive = store((s) => s.stopLive);

  useEffect(() => {
    if (target.processId && lines.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target.processKind, target.processId]);

  useEffect(() => () => stopLive(), [stopLive]);

  const series: Series[] = useMemo(() => {
    const byName = new Map<string, MetricsHistoryLine[]>();
    for (const l of lines) {
      const arr = byName.get(l.name);
      if (arr) arr.push(l);
      else byName.set(l.name, [l]);
    }
    return [...byName.entries()].map(([name, ls]) => {
      const key = primaryKey(ls[0]) ?? "VALUE";
      const hasPercentiles = ls.some((l) => l.percentiles);
      const points = ls.map((l) => {
        const v = l.measurements[key] ?? 0;
        const p = l.percentiles;
        return {
          t: clock(l.timestamp),
          v,
          p50: p?.["0.5"],
          p95: p?.["0.95"],
          p99: p?.["0.99"],
          __label: `${name} · ${clock(l.timestamp)}`,
          __tip: [
            [key.toLowerCase(), formatValue(name, key, v)],
            ...(p
              ? Object.entries(p).map(
                  ([q, val]) =>
                    [`p${Math.round(Number(q) * 100)}`, `${(val * 1000).toFixed(1)} ms`] as [
                      string,
                      string,
                    ],
                )
              : []),
          ] as Array<[string, string]>,
        };
      });
      return { name, type: ls[0].type, key, points, hasPercentiles };
    });
  }, [lines]);

  if (!target.processId) {
    return (
      <p className="font-mono text-xs text-muted-foreground">
        enter a {target.processKind.toLowerCase()} address (host:port) above to load its metrics
        history
      </p>
    );
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <span className="hud-label text-muted-foreground">
          {lines.length} samples · {series.length} meters
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => loadOlder()}
            className="rounded-sm border border-primary/20 bg-background px-2 py-1 font-mono text-[10px] uppercase tracking-widest text-muted-foreground transition-colors hover:text-foreground"
          >
            load older
          </button>
          <button
            type="button"
            onClick={() => (live ? stopLive() : startLive())}
            className={cn(
              "rounded-sm border px-2 py-1 font-mono text-[10px] uppercase tracking-widest transition-colors",
              live
                ? "border-status-ok/40 bg-status-ok/10 text-status-ok"
                : "border-primary/20 bg-primary/10 text-primary hover:bg-primary/20",
            )}
          >
            {live ? "live · polling" : "go live"}
          </button>
        </div>
      </div>

      {error && <p className="mb-3 font-mono text-xs text-status-bad">{error}</p>}
      {loading && lines.length === 0 && (
        <p className="font-mono text-xs text-muted-foreground">loading history…</p>
      )}
      {!loading && lines.length === 0 && !error && (
        <p className="font-mono text-xs text-muted-foreground">no samples in window</p>
      )}

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        {series.map((s) => (
          <Panel
            key={s.name}
            title={s.name}
            aside={
              <span className="hud-label text-muted-foreground">
                {s.type.toLowerCase()} · {s.key.toLowerCase()}
              </span>
            }
          >
            <div className="h-52 p-3">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={s.points} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
                  <defs>
                    <linearGradient id={`grad-${s.name}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.45} />
                      <stop offset="100%" stopColor="var(--primary)" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid
                    stroke="var(--status-muted)"
                    strokeOpacity={0.15}
                    vertical={false}
                  />
                  <XAxis dataKey="t" {...AXIS} tickLine={false} minTickGap={40} />
                  <YAxis
                    {...AXIS}
                    width={54}
                    tickLine={false}
                    tickFormatter={(v: number) => formatValue(s.name, s.key, v)}
                  />
                  <Tooltip content={<ChartTooltip />} />
                  <Area
                    type="monotone"
                    dataKey="v"
                    stroke="var(--primary)"
                    strokeWidth={1.5}
                    fill={`url(#grad-${s.name})`}
                    isAnimationActive={false}
                  />
                  {s.hasPercentiles && (
                    <Area
                      type="monotone"
                      dataKey="p99"
                      stroke="var(--status-warn)"
                      strokeWidth={1}
                      fill="none"
                      isAnimationActive={false}
                    />
                  )}
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </Panel>
        ))}
      </div>
    </div>
  );
}
