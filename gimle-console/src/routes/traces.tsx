import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { z } from "zod";

import { PageContainer, PageHeader, Panel, StatTile } from "@/components/page-shell";
import { ProcessPicker, defaultProcessTarget } from "@/components/process-picker";
import { cn } from "@/lib/utils";
import { tracesRepo } from "@/repositories";
import { useInstancesStore } from "@/stores/useInstancesStore";
import { useTracesStore } from "@/stores/useTracesStore";
import type { ProcessTarget, TraceSpanLine } from "@/types";
import {
  coverageSummary,
  followTraceAcrossProcesses,
  groupSpansByProcess,
  processTargetKey,
  spanDepths,
  workerTargetsFromInstances,
  type TraceFollowResult,
} from "./-trace-follow";

// See routes/metrics.tsx's own identical schema for why this exists: a deep link into a specific
// process' trace history (e.g. an instance's own "view worker traces" link), falling back to the
// default target for a bare /traces navigation with no query string.
export const processTargetSearchSchema = z.object({
  processKind: z.enum(["CONTROLPLANE", "FAFNIR", "STORE", "AGENT", "WORKER"]),
  processId: z.string(),
});
export const processTargetSearchSchemaWithFallback = processTargetSearchSchema.catch(() =>
  defaultProcessTarget(),
);

export const Route = createFileRoute("/traces")({
  validateSearch: (search) => processTargetSearchSchemaWithFallback.parse(search),
  head: () => ({
    meta: [
      { title: "Traces — Gimlé Console" },
      {
        name: "description",
        content:
          "Span history per control-plane process: trace id, span name, kind, status and time.",
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

function TraceTable({
  target,
  onFollow,
}: {
  target: ProcessTarget;
  onFollow: (traceId: string) => void;
}) {
  const store = useTracesStore(target);
  const lines = store((s) => s.lines);
  const loading = store((s) => s.loading);
  const error = store((s) => s.error);
  const live = store((s) => s.live);
  const loadFirstPage = store((s) => s.loadFirstPage);
  const loadOlder = store((s) => s.loadOlder);
  const startLive = store((s) => s.startLive);
  const stopLive = store((s) => s.stopLive);

  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({
    key: "timestamp",
    desc: true,
  });
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  useEffect(() => {
    if (target.processId && lines.length === 0) loadFirstPage();
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
    return [...filtered].sort(
      (a, b) => dir * String(a[sort.key]).localeCompare(String(b[sort.key])),
    );
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
        <StatTile
          label="Stream"
          value={live ? "live" : "paused"}
          tone={live ? "primary" : "muted"}
        />
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
                  <td className="px-3 py-1.5 font-mono text-[11px]">
                    <button
                      type="button"
                      onClick={() => onFollow(l.traceId)}
                      title={`follow ${l.traceId} across worker processes`}
                      className="text-signal underline decoration-dotted underline-offset-2 hover:text-foreground"
                    >
                      {l.traceId.slice(0, 12)}…
                    </button>
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
                    {!target.processId
                      ? `enter a ${target.processKind.toLowerCase()} address (host:port) above to load its trace history`
                      : loading
                        ? "loading spans…"
                        : error
                          ? error
                          : "no spans in window"}
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


/**
 * Assembling one trace from the several per-process histories it is scattered across. There is no
 * server-side trace search: this fans the same GET /traces-history/{kind}/{id} call out over every
 * worker the console can currently name, so what it shows is bounded by that list and by how far
 * back each process' own history is walked -- stated plainly in the panel rather than left implied.
 */
function TraceFollowPanel({
  traceId,
  onClose,
}: {
  traceId: string;
  onClose: () => void;
}) {
  const instances = useInstancesStore((s) => s.items);
  const loadInstances = useInstancesStore((s) => s.loadFirstPage);
  const [result, setResult] = useState<TraceFollowResult | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (instances.length === 0) loadInstances();
  }, [instances.length, loadInstances]);

  const targets = useMemo(() => workerTargetsFromInstances(instances), [instances]);

  const follow = useCallback(async () => {
    setLoading(true);
    try {
      setResult(await followTraceAcrossProcesses(tracesRepo, traceId, targets));
    } finally {
      setLoading(false);
    }
  }, [traceId, targets]);

  useEffect(() => {
    if (targets.length > 0) follow();
  }, [follow, targets.length]);

  const groups = useMemo(() => (result ? groupSpansByProcess(result.spans) : []), [result]);
  const depths = useMemo(() => (result ? spanDepths(result.spans) : new Map()), [result]);

  return (
    <Panel
      className="mb-6"
      title="Follow trace"
      aside={
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => follow()}
            className="rounded-sm border border-primary/20 bg-background px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:text-foreground"
          >
            refresh
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded-sm border border-primary/20 bg-background px-2 py-0.5 font-mono text-[10px] uppercase tracking-widest text-muted-foreground hover:text-foreground"
          >
            close
          </button>
        </div>
      }
    >
      <div className="border-b border-primary/10 p-3">
        <div className="font-mono text-[11px] text-signal break-all">{traceId}</div>
        <div className="mt-1 font-mono text-[10px] text-muted-foreground">
          {loading ? "searching worker processes…" : result ? coverageSummary(result) : "…"}
        </div>
      </div>

      <div className="max-h-[420px] overflow-auto">
        {groups.map((group) => (
          <div key={processTargetKey(group.target)} className="border-b border-primary/5">
            <div className="bg-primary/5 px-3 py-1 font-mono text-[10px] uppercase tracking-widest text-primary">
              worker / {group.target.processId}
            </div>
            {group.spans.map((span) => (
              <div
                key={span.spanId}
                className="flex flex-wrap items-baseline gap-2 px-3 py-1 font-mono text-[11px]"
              >
                <span
                  className="text-foreground"
                  style={{ paddingLeft: `${(depths.get(span.spanId) ?? 0) * 12}px` }}
                >
                  {span.name}
                </span>
                <span className="text-[10px] uppercase tracking-widest text-muted-foreground">
                  {span.kind.toLowerCase()}
                </span>
                <span
                  className={cn("text-[10px] uppercase tracking-widest", statusTone(span.status))}
                >
                  {span.status.toLowerCase()}
                </span>
                <span className="tabular-nums text-muted-foreground">{clock(span.timestamp)}</span>
              </div>
            ))}
          </div>
        ))}
        {result && result.spans.length === 0 && !loading && (
          <p className="px-3 py-3 font-mono text-[11px] text-muted-foreground">
            no spans for this trace in any reachable worker&apos;s loaded history window
          </p>
        )}
      </div>

      <div className="border-t border-primary/10 p-3 font-mono text-[10px] leading-relaxed text-muted-foreground">
        <p>
          Only WORKER processes are searched: the service fabric is the sole place spans are created
          today, so no other process kind has trace history to contribute.
        </p>
        <p>
          Searchable workers come from the instance list ({targets.length} found), so a worker with
          no currently listed instance — one already torn down, or whose handshake has not reported
          a worker id — cannot be reached from here even if Muninn still holds its spans.
        </p>
        <p>
          Each worker&apos;s history is walked only a few pages back per search; a span older than
          that window is not shown. There is no server-side trace search to ask instead.
        </p>
        {result && result.failures.length > 0 && (
          <p className="text-status-bad">
            unreachable:{" "}
            {result.failures.map((f) => `${f.target.processId} (${f.message})`).join(", ")}
          </p>
        )}
      </div>
    </Panel>
  );
}

function Traces() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  const [target, setTarget] = useState<ProcessTarget>(search);
  const [followedTraceId, setFollowedTraceId] = useState<string | null>(null);
  // See routes/metrics.tsx's own identical effect: a useState initializer only runs once, so a
  // second navigation to /traces with a different search string (e.g. another "view worker
  // traces" link while this route is already mounted) needs this to actually take effect.
  useEffect(() => {
    setTarget(search);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search.processKind, search.processId]);
  function updateTarget(t: ProcessTarget) {
    setTarget(t);
    navigate({ to: ".", search: t, replace: true });
  }

  return (
    <PageContainer>
      <PageHeader
        title="Trace History"
        eyebrow="Gimlé // Traces"
        subtitle="Span records per process. Select a trace id to follow it across every worker this console can name."
        actions={<ProcessPicker value={target} onChange={updateTarget} />}
      />
      {followedTraceId && (
        <TraceFollowPanel
          key={followedTraceId}
          traceId={followedTraceId}
          onClose={() => setFollowedTraceId(null)}
        />
      )}
      <TraceTable
        key={`${target.processKind}:${target.processId}`}
        target={target}
        onFollow={setFollowedTraceId}
      />
    </PageContainer>
  );
}
