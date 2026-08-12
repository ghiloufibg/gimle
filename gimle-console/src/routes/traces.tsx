import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { ProcessPicker, defaultProcessTarget } from "@/components/process-picker";
import { cn } from "@/lib/utils";
import { useTracesStore } from "@/stores/useTracesStore";
import type { ProcessTarget, TraceSpanLine } from "@/types";

export const Route = createFileRoute("/traces")({
  head: () => ({
    meta: [
      { title: "Traces — Gimlé Console" },
      {
        name: "description",
        content: "Span history per control-plane process: trace id, span name, kind, status and time.",
      },
      { property: "og:title", content: "Traces — Gimlé Console" },
      {
        property: "og:description",
        content: "Flat, sortable span table from the cluster trace history API.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Traces,
});

type SortKey = "timestamp" | "traceId" | "name" | "kind" | "status";

function statusTone(status: string): string {
  if (status === "ERROR") return "text-status-bad";
  if (status === "OK") return "text-status-ok";
  return "text-muted-foreground";
}

function clock(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString(undefined, { hour12: false });
}

function TraceTable({ target }: { target: ProcessTarget }) {
  const store = useTracesStore(target);
  const lines = store((s) => s.lines);
  const loading = store((s) => s.loading);
  const error = store((s) => s.error);
  const live = store((s) => s.live);
  const loadFirstPage = store((s) => s.loadFirstPage);
  const loadOlder = store((s) => s.loadOlder);
  const startLive = store((s) => s.startLive);
  const stopLive = store((s) => s.stopLive);

  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: "timestamp", desc: true });
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  useEffect(() => {
    if (lines.length === 0) loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target.processKind, target.processId]);

  useEffect(() => () => stopLive(), [stopLive]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const filtered = lines.filter((l) => {
      if (statusFilter !== "ALL" && l.status !== statusFilter) return false;
      if (!q) return true;
      return (
        l.traceId.toLowerCase().includes(q) ||
        l.spanId.toLowerCase().includes(q) ||
        l.name.toLowerCase().includes(q)
      );
    });
    const dir = sort.desc ? -1 : 1;
    return [...filtered].sort((a, b) => dir * String(a[sort.key]).localeCompare(String(b[sort.key])));
  }, [lines, query, statusFilter, sort]);

  const errors = lines.filter((l) => l.status === "ERROR").length;
  const traceCount = new Set(lines.map((l) => l.traceId)).size;

  function header(key: SortKey, label: string) {
    return (
      <button
        type="button"
        onClick={() => setSort((s) => ({ key, desc: s.key === key ? !s.desc : true }))}
        className="hud-label text-muted-foreground transition-colors hover:text-foreground"
      >
        {label}
        {sort.key === key ? (sort.desc ? " ↓" : " ↑") : ""}
      </button>
    );
  }

  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-px bg-primary/10 lg:grid-cols-4">
        <StatTile label="Spans in window" value={lines.length} tone="primary" />
        <StatTile label="Distinct traces" value={traceCount} />
        <StatTile label="Error spans" value={errors} tone={errors > 0 ? "alarm" : "default"} />
        <StatTile label="Stream" value={live ? "live" : "paused"} tone={live ? "primary" : "muted"} />
      </div>

      <Panel
        title="Span history"
        aside={<span className="hud-label text-muted-foreground">{rows.length} shown</span>}
      >
        <div className="flex flex-wrap items-center gap-2 border-b border-primary/10 p-3">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="filter trace id / span name"
            className="h-8 min-w-56 flex-1 rounded-sm border border-primary/20 bg-background px-2 font-mono text-xs"
          />
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            aria-label="Status filter"
            className="h-8 rounded-sm border border-primary/20 bg-background px-2 font-mono text-[10px] uppercase tracking-widest"
          >
            {["ALL", "OK", "UNSET", "ERROR"].map((s) => (
              <option key={s} value={s}>
                {s.toLowerCase()}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => loadOlder()}
            className="h-8 rounded-sm border border-primary/20 bg-background px-2 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:text-foreground"
          >
            load older
          </button>
          <button
            type="button"
            onClick={() => (live ? stopLive() : startLive())}
            className={cn(
              "h-8 rounded-sm border px-2 font-mono text-[10px] uppercase tracking-widest",
              live
                ? "border-status-ok/40 bg-status-ok/10 text-status-ok"
                : "border-primary/20 bg-primary/10 text-primary hover:bg-primary/20",
            )}
          >
            {live ? "live · polling" : "go live"}
          </button>
        </div>

        <div className="max-h-[560px] overflow-auto">
          <table className="w-full border-collapse text-left">
            <thead className="sticky top-0 bg-background/95 backdrop-blur">
              <tr className="border-b border-primary/10">
                <th className="px-3 py-2">{header("timestamp", "time")}</th>
                <th className="px-3 py-2">{header("traceId", "trace")}</th>
                <th className="px-3 py-2">{header("name", "span")}</th>
                <th className="px-3 py-2">{header("kind", "kind")}</th>
                <th className="px-3 py-2">{header("status", "status")}</th>
                <th className="px-3 py-2">
                  <span className="hud-label text-muted-foreground">parent</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((l: TraceSpanLine) => (
                <tr key={l.spanId} className="border-b border-primary/5 hover:bg-primary/5">
                  <td className="whitespace-nowrap px-3 py-1.5 font-mono text-[11px] tabular-nums text-muted-foreground">
                    {clock(l.timestamp)}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-signal">
                    {l.traceId.slice(0, 12)}…
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-foreground">{l.name}</td>
                  <td className="px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
                    {l.kind.toLowerCase()}
                  </td>
                  <td
                    className={cn(
                      "px-3 py-1.5 font-mono text-[10px] uppercase tracking-widest",
                      statusTone(l.status),
                    )}
                  >
                    {l.status.toLowerCase()}
                  </td>
                  <td className="px-3 py-1.5 font-mono text-[11px] text-muted-foreground">
                    {l.parentSpanId ? `${l.parentSpanId.slice(0, 8)}…` : "root"}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-3 py-4 font-mono text-[11px] text-muted-foreground">
                    {loading ? "loading spans…" : error ? error : "no spans in window"}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>
    </>
  );
}

function Traces() {
  const [target, setTarget] = useState<ProcessTarget>(defaultProcessTarget);

  return (
    <PageContainer>
      <PageHeader
        title="Trace History"
        eyebrow="Gimlé // Traces"
        subtitle="Flat span records per process. Timestamps are span end times — no duration is reported yet."
        actions={<ProcessPicker value={target} onChange={setTarget} />}
      />
      <TraceTable key={`${target.processKind}:${target.processId}`} target={target} />
    </PageContainer>
  );
}
