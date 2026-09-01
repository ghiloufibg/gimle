import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";
import type { CrashDump, LogCategory, LogFilter, LogLevel, LogLine, LogTarget } from "@/types";
import { useLogStore } from "@/stores/useLogStore";
import { logsRepo } from "@/repositories";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Play, Pause, ArrowDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { fmtBytes, fmtRelativeTime } from "@/lib/format";
import { describeLogFilter, isLogFilterActive, LOG_LEVELS, toLogFilter } from "@/lib/log-filter";
import { Input } from "@/components/ui/input";

// The content filter lives in the URL, so a filtered view is bookmarkable and shareable the same
// way the target and category already are. Both parts are optional and validated leniently: an
// unknown level in a hand-edited URL degrades to "no level constraint" rather than throwing the
// whole route into the error boundary.
const filterSearch = {
  level: z.enum(["TRACE", "DEBUG", "INFO", "WARN", "ERROR"]).optional().catch(undefined),
  contains: z.string().optional().catch(undefined),
};

// Exported for logs.test.ts -- pure Zod/branching logic, no rendering involved, so it's testable
// under this project's node-environment vitest config without needing component-rendering infra.
export const searchSchema = z.union([
  z.object({
    kind: z.literal("instance"),
    deploymentName: z.string(),
    instanceIndex: z.coerce.number().int(),
    category: z.enum(["APPLICATION", "PLATFORM"]).default("APPLICATION"),
    ...filterSearch,
  }),
  z.object({
    kind: z.literal("node"),
    nodeId: z.string(),
    category: z.enum(["PLATFORM", "SYSTEM"]).default("PLATFORM"),
    ...filterSearch,
  }),
  z.object({
    kind: z.literal("controlplane"),
    category: z.enum(["PLATFORM", "SYSTEM"]).default("PLATFORM"),
    ...filterSearch,
  }),
]);

// A bare /logs navigation (typed, bookmarked, or shared with no query string) has no target to
// resolve -- fall back to the control plane's own PLATFORM log rather than throwing out of
// validateSearch/beforeLoad, which would otherwise crash the whole app into the root error
// boundary. "controlplane" is the only target needing no further parameters.
export const FALLBACK_SEARCH = { kind: "controlplane" as const, category: "PLATFORM" as const };
export const searchSchemaWithFallback = searchSchema.catch(FALLBACK_SEARCH);

export const Route = createFileRoute("/logs")({
  validateSearch: (search) => searchSchemaWithFallback.parse(search),
  head: () => ({
    meta: [
      { title: "Logs — Gimlé Console" },
      { name: "description", content: "Live log tail." },
      { name: "robots", content: "noindex" },
    ],
  }),
  component: LogsPage,
});

const LEVEL_STYLE: Record<string, string> = {
  ERROR: "text-status-bad",
  WARN: "text-status-warn",
  INFO: "text-foreground",
  DEBUG: "text-muted-foreground",
  TRACE: "text-muted-foreground/70",
};

type LogsSearch = z.infer<typeof searchSchema>;

/**
 * Splits one flat search object into the two things the repositories take separately: the target
 * (what stream to read) and the filter (what to keep from it). Exported for the route's own tests
 * -- pure, no rendering involved.
 */
export function targetOf(search: LogsSearch): LogTarget {
  const { level: _level, contains: _contains, ...target } = search;
  return target as LogTarget;
}

export function filterOf(search: LogsSearch): LogFilter {
  return toLogFilter(search.level ?? null, search.contains ?? null);
}

/** The empty-state line shown when a query legitimately matched nothing, rather than silence. */
export function emptyStateMessage(filter: LogFilter): string {
  return isLogFilterActive(filter)
    ? `No log lines matched ${describeLogFilter(filter)}.`
    : "No log lines yet.";
}

function targetTitle(t: LogTarget): string {
  if (t.kind === "instance") return `${t.deploymentName} #${t.instanceIndex}`;
  if (t.kind === "node") return t.nodeId;
  return "control plane";
}
export function validCategories(kind: LogTarget["kind"]): LogCategory[] {
  if (kind === "instance") return ["APPLICATION", "PLATFORM"];
  // The control plane has no supervised child process whose stdout SYSTEM capture would exist --
  // ApiServer.handleControlPlaneLogs hard-rejects anything but PLATFORM for this kind. Node-level
  // SYSTEM logs are real (merged per-instance raw-capture files), so "node" keeps both.
  if (kind === "controlplane") return ["PLATFORM"];
  return ["PLATFORM", "SYSTEM"];
}

function LogsPage() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  const target = targetOf(search);
  const filter = filterOf(search);
  const store = useLogStore(target, filter);
  const state = store();
  const { lines, loading, following, error } = state;

  const [containsDraft, setContainsDraft] = useState(search.contains ?? "");
  useEffect(() => {
    setContainsDraft(search.contains ?? "");
  }, [search.contains]);
  function applyContains() {
    const next = containsDraft.trim() === "" ? undefined : containsDraft;
    if (next === search.contains) return;
    navigate({ search: (prev) => ({ ...prev, contains: next }) });
  }

  const scrollRef = useRef<HTMLDivElement>(null);
  const [stickToBottom, setStickToBottom] = useState(true);
  const [newLineCount, setNewLineCount] = useState(0);
  const prevLenRef = useRef(0);

  useEffect(() => {
    if (state.lines.length === 0) state.loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    target.kind,
    target.kind === "instance" ? target.deploymentName : undefined,
    target.kind === "node" ? target.nodeId : undefined,
    target.category,
    search.level,
    search.contains,
  ]);

  useEffect(() => {
    return () => {
      store.getState().unfollow();
    };
  }, [store]);

  // handle new lines: scroll or count them
  useEffect(() => {
    const el = scrollRef.current;
    const added = lines.length - prevLenRef.current;
    prevLenRef.current = lines.length;
    if (!el) return;
    if (stickToBottom) {
      el.scrollTop = el.scrollHeight;
    } else if (added > 0 && following) {
      setNewLineCount((n) => n + added);
    }
  }, [lines.length, stickToBottom, following]);

  function onScroll() {
    const el = scrollRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 20;
    setStickToBottom(atBottom);
    if (atBottom) setNewLineCount(0);
  }

  function jumpToBottom() {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    setStickToBottom(true);
    setNewLineCount(0);
  }

  const cats = useMemo(() => validCategories(target.kind), [target.kind]);

  const [crashDumps, setCrashDumps] = useState<CrashDump[]>([]);
  useEffect(() => {
    let cancelled = false;
    if (target.kind === "instance") {
      logsRepo
        .listCrashDumps(target)
        .then((dumps) => {
          if (!cancelled) setCrashDumps(dumps);
        })
        .catch(() => {
          if (!cancelled) setCrashDumps([]);
        });
    } else {
      setCrashDumps([]);
    }
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    target.kind,
    target.kind === "instance" ? target.deploymentName : undefined,
    target.kind === "instance" ? target.instanceIndex : undefined,
  ]);

  return (
    <PageContainer>
      <PageHeader
        title={<span className="font-mono">logs · {targetTitle(target)}</span>}
        subtitle={`category: ${target.category}`}
        actions={
          <>
            {target.kind === "instance" && (
              <Button variant="outline" size="sm" asChild>
                <Link
                  to="/instances/$name/$idx"
                  params={{ name: target.deploymentName, idx: String(target.instanceIndex) }}
                >
                  Back
                </Link>
              </Button>
            )}
            {target.kind === "node" && (
              <Button variant="outline" size="sm" asChild>
                <Link to="/nodes/$nodeId" params={{ nodeId: target.nodeId }}>
                  Back
                </Link>
              </Button>
            )}
            {target.kind === "controlplane" && (
              <Button variant="outline" size="sm" asChild>
                <Link to="/controlplane">Back</Link>
              </Button>
            )}
            <Button
              size="sm"
              variant={following ? "secondary" : "default"}
              onClick={() => (following ? state.unfollow() : state.follow())}
            >
              {following ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
              {following ? "Pause" : "Follow"}
            </Button>
          </>
        }
      />

      <div className="mb-2 flex items-center gap-1">
        {cats.map((c) => {
          const to =
            target.kind === "instance"
              ? {
                  to: "/logs" as const,
                  search: {
                    kind: "instance" as const,
                    deploymentName: target.deploymentName,
                    instanceIndex: target.instanceIndex,
                    category: c as "APPLICATION" | "PLATFORM",
                    level: search.level,
                    contains: search.contains,
                  },
                }
              : target.kind === "node"
                ? {
                    to: "/logs" as const,
                    search: {
                      kind: "node" as const,
                      nodeId: target.nodeId,
                      category: c as "PLATFORM" | "SYSTEM",
                      level: search.level,
                      contains: search.contains,
                    },
                  }
                : {
                    to: "/logs" as const,
                    search: {
                      kind: "controlplane" as const,
                      category: c as "PLATFORM" | "SYSTEM",
                      level: search.level,
                      contains: search.contains,
                    },
                  };
          const active = target.category === c;
          return (
            <Link
              key={c}
              {...to}
              className={cn(
                "px-2.5 py-1 text-[11px] rounded-t border-b-2 font-medium uppercase tracking-wider",
                active
                  ? "border-primary text-foreground bg-card"
                  : "border-transparent text-muted-foreground hover:text-foreground",
              )}
            >
              {c}
            </Link>
          );
        })}
      </div>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-1.5 text-[11px] uppercase tracking-wider text-muted-foreground">
          Level
          <select
            value={search.level ?? ""}
            onChange={(e) =>
              navigate({
                search: (prev) => ({
                  ...prev,
                  level: e.target.value === "" ? undefined : (e.target.value as LogLevel),
                }),
              })
            }
            className="h-7 rounded border border-border bg-background px-2 font-mono text-[11px]"
          >
            <option value="">all</option>
            {LOG_LEVELS.map((level) => (
              <option key={level} value={level}>
                {level}+
              </option>
            ))}
          </select>
        </label>
        <Input
          value={containsDraft}
          onChange={(e) => setContainsDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") applyContains();
          }}
          onBlur={applyContains}
          placeholder="contains… (plain text, not a regex)"
          className="h-7 max-w-xs font-mono text-[11px]"
        />
        {isLogFilterActive(filter) && (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-[11px]"
            onClick={() => {
              setContainsDraft("");
              navigate({ search: (prev) => ({ ...prev, level: undefined, contains: undefined }) });
            }}
          >
            Clear filter
          </Button>
        )}
      </div>

      <div className="relative rounded border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border px-2 py-1 text-[11px] text-muted-foreground">
          <Button
            variant="ghost"
            size="sm"
            className="h-6 text-[11px]"
            onClick={() => state.loadOlder()}
            disabled={loading}
          >
            {loading ? "Loading…" : "Load older"}
          </Button>
          <span className="font-mono">
            {lines.length} lines {following && "· live"}
          </span>
        </div>
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className="h-[62vh] overflow-y-auto font-mono text-[11px] leading-relaxed"
        >
          {error && (
            <div className="p-3">
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            </div>
          )}
          {lines.map((line, i) => (
            <LogRow key={i} line={line} />
          ))}
          {lines.length === 0 && !loading && !error && (
            <div className="p-6 text-center text-muted-foreground">{emptyStateMessage(filter)}</div>
          )}
        </div>
        {!stickToBottom && newLineCount > 0 && (
          <button
            onClick={jumpToBottom}
            className="absolute bottom-3 right-3 inline-flex items-center gap-1 rounded-full bg-primary text-primary-foreground px-3 py-1 text-xs shadow"
          >
            <ArrowDown className="h-3 w-3" />
            {newLineCount} new
          </button>
        )}
      </div>

      {target.kind === "instance" && crashDumps.length > 0 && (
        <div className="mt-4 rounded border border-status-bad/40 bg-status-bad/5">
          <div className="border-b border-status-bad/30 px-3 py-1.5 text-[11px] font-medium uppercase tracking-wider text-status-bad">
            Crash dumps
          </div>
          <ul className="divide-y divide-border/40">
            {crashDumps.map((dump) => (
              <li
                key={dump.name}
                className="flex items-center justify-between px-3 py-1.5 text-[11px]"
              >
                <span className="font-mono">{dump.name}</span>
                <span className="flex items-center gap-3 text-muted-foreground">
                  <span>{fmtBytes(dump.sizeBytes)}</span>
                  <span>{fmtRelativeTime(dump.lastModified)}</span>
                  <a
                    href={`/logs/instances/${target.deploymentName}/${target.instanceIndex}/crashdumps/${dump.name}`}
                    download={dump.name}
                    className="text-primary hover:underline"
                  >
                    Download
                  </a>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </PageContainer>
  );
}

function LogRow({ line }: { line: LogLine }) {
  if ("raw" in line) {
    return (
      <div className="border-b border-border/40 px-2 py-0.5 bg-muted/20 text-muted-foreground italic">
        <span className="text-muted-foreground/60 mr-2">{fmtTs(line.timestamp)}</span>
        {line.raw}
      </div>
    );
  }
  return (
    <div
      className={cn("border-b border-border/40 px-2 py-0.5 flex gap-2", LEVEL_STYLE[line.level])}
    >
      <span className="text-muted-foreground shrink-0">{fmtTs(line.timestamp)}</span>
      <span className="shrink-0 w-12 font-semibold">{line.level}</span>
      <span className="shrink-0 max-w-[220px] truncate text-muted-foreground">{line.logger}</span>
      <span className="shrink-0 max-w-[180px] truncate text-muted-foreground/70">
        [{line.thread}]
      </span>
      <span className="min-w-0">{line.message}</span>
    </div>
  );
}

function fmtTs(iso: string) {
  const d = new Date(iso);
  return d.toISOString().slice(11, 23);
}
