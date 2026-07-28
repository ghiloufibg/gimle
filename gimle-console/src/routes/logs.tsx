import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useMemo, useRef, useState } from "react";
import { z } from "zod";
import type { LogCategory, LogLine, LogTarget } from "@/types";
import { useLogStore } from "@/stores/useLogStore";
import { PageContainer, PageHeader } from "@/components/page-shell";
import { Button } from "@/components/ui/button";
import { Play, Pause, ArrowDown } from "lucide-react";
import { cn } from "@/lib/utils";

const searchSchema = z.union([
  z.object({
    kind: z.literal("instance"),
    deploymentName: z.string(),
    instanceIndex: z.coerce.number().int(),
    category: z.enum(["APPLICATION", "PLATFORM"]).default("APPLICATION"),
  }),
  z.object({
    kind: z.literal("node"),
    nodeId: z.string(),
    category: z.enum(["PLATFORM", "SYSTEM"]).default("PLATFORM"),
  }),
  z.object({
    kind: z.literal("controlplane"),
    category: z.enum(["PLATFORM", "SYSTEM"]).default("PLATFORM"),
  }),
]);

export const Route = createFileRoute("/logs")({
  validateSearch: searchSchema,
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

function targetTitle(t: LogTarget): string {
  if (t.kind === "instance") return `${t.deploymentName} #${t.instanceIndex}`;
  if (t.kind === "node") return t.nodeId;
  return "control plane";
}
function validCategories(kind: LogTarget["kind"]): LogCategory[] {
  if (kind === "instance") return ["APPLICATION", "PLATFORM"];
  return ["PLATFORM", "SYSTEM"];
}

function LogsPage() {
  const search = Route.useSearch();
  const target = search as LogTarget;
  const store = useLogStore(target);
  const state = store();
  const { lines, loading, following } = state;

  const scrollRef = useRef<HTMLDivElement>(null);
  const [stickToBottom, setStickToBottom] = useState(true);
  const [newLineCount, setNewLineCount] = useState(0);
  const prevLenRef = useRef(0);

  useEffect(() => {
    if (state.lines.length === 0) state.loadFirstPage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target.kind, (target as any).deploymentName, (target as any).nodeId, target.category]);

  useEffect(() => {
    return () => {
      state.unfollow();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
                  },
                }
              : target.kind === "node"
                ? {
                    to: "/logs" as const,
                    search: {
                      kind: "node" as const,
                      nodeId: target.nodeId,
                      category: c as "PLATFORM" | "SYSTEM",
                    },
                  }
                : {
                    to: "/logs" as const,
                    search: {
                      kind: "controlplane" as const,
                      category: c as "PLATFORM" | "SYSTEM",
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
          {lines.map((line, i) => (
            <LogRow key={i} line={line} />
          ))}
          {lines.length === 0 && !loading && (
            <div className="p-6 text-center text-muted-foreground">No log lines yet.</div>
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
    <div className={cn("border-b border-border/40 px-2 py-0.5 flex gap-2", LEVEL_STYLE[line.level])}>
      <span className="text-muted-foreground shrink-0">{fmtTs(line.timestamp)}</span>
      <span className="shrink-0 w-12 font-semibold">{line.level}</span>
      <span className="shrink-0 max-w-[220px] truncate text-muted-foreground">{line.logger}</span>
      <span className="shrink-0 max-w-[180px] truncate text-muted-foreground/70">[{line.thread}]</span>
      <span className="min-w-0">{line.message}</span>
    </div>
  );
}

function fmtTs(iso: string) {
  const d = new Date(iso);
  return d.toISOString().slice(11, 23);
}
