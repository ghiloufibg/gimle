import { Fragment, useMemo, useState } from "react";

import type { Blueprint, Problem, Severity } from "@/lib/blueprint";
import { collapseProblems, tally, type ProblemRow } from "@/lib/problemView";
import { cn } from "@/lib/utils";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

function severityClass(severity: Problem["severity"]): string {
  if (severity === "error") return "text-status-bad";
  if (severity === "warning") return "text-status-warn";
  return "text-status-info";
}

/**
 * Names the node a problem points at so two replicas of the same role can be
 * told apart: an unnamed role falls back to its machine and port, and any
 * remaining ambiguity is resolved with its index among its own kind.
 */
function targetLabel(blueprint: Blueprint, nodeId: string | undefined): string {
  if (!nodeId) return "blueprint";
  const node = blueprint.nodes.find((n) => n.id === nodeId);
  if (!node) return nodeId;
  const d = node.data as unknown as Record<string, unknown>;
  const named = [d.name, d.id, d.nodeId, d.key].find(
    (v) => typeof v === "string" && v.trim() !== "",
  ) as string | undefined;

  const sameKind = blueprint.nodes.filter((n) => n.kind === node.kind);
  const index = sameKind.findIndex((n) => n.id === node.id) + 1;

  if (!named) {
    const machine = typeof d.machine === "string" && d.machine ? d.machine : null;
    const port = [d.port, d.clientPort, d.raftPort, d.gossipPort].find(
      (v) => typeof v === "number",
    ) as number | undefined;
    const where = [machine, port ? String(port) : null].filter(Boolean).join(":");
    const suffix = where || "unnamed";
    return sameKind.length > 1 ? `${node.kind}/${suffix} #${index}` : `${node.kind}/${suffix}`;
  }

  const duplicates = sameKind.filter((n) => {
    const nd = n.data as unknown as Record<string, unknown>;
    return [nd.name, nd.id, nd.nodeId, nd.key].some((v) => v === named);
  }).length;
  return duplicates > 1 ? `${node.kind}/${named} #${index}` : `${node.kind}/${named}`;
}

function targetsLabel(blueprint: Blueprint, row: ProblemRow): string {
  if (row.file) return row.file;
  if (row.nodeIds.length === 0) return "blueprint";
  return row.nodeIds.map((id) => targetLabel(blueprint, id)).join(", ");
}

type Filter = "all" | Severity;

export function ProblemsDrawer({ blueprint }: { blueprint: Blueprint }) {
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const hilmirProblems = useValidationStore((s) => s.serverProblems);
  const hilmir = useValidationStore((s) => s.hilmir);
  const validateWithHilmir = useValidationStore((s) => s.validateWithHilmir);
  const select = useBlueprintStore((s) => s.select);
  const selectedId = useBlueprintStore((s) => s.selectedId);
  const [filter, setFilter] = useState<Filter>("all");
  const [grouped, setGrouped] = useState(false);

  const rows = useMemo(
    () => collapseProblems(ivaldiProblems, hilmirProblems),
    [ivaldiProblems, hilmirProblems],
  );
  const counts = useMemo(() => tally(rows), [rows]);
  const shown = filter === "all" ? rows : rows.filter((r) => r.severity === filter);

  const groups = useMemo(() => {
    const map = new Map<string, ProblemRow[]>();
    for (const row of shown) map.set(row.code, [...(map.get(row.code) ?? []), row]);
    return [...map.entries()];
  }, [shown]);

  const chip = (value: Filter, label: string, tone: string) => (
    <button
      key={value}
      onClick={() => setFilter(value)}
      className={cn(
        "rounded-sm border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest",
        filter === value ? "border-primary text-foreground" : "border-border text-muted-foreground",
        tone,
      )}
    >
      {label}
    </button>
  );

  const row = (r: ProblemRow) => (
    <tr
      key={r.key}
      onClick={() => r.nodeIds[0] && select(r.nodeIds[0])}
      className={cn(
        "cursor-pointer border-b border-border/60 hover:bg-accent/40",
        selectedId && r.nodeIds.includes(selectedId) && "bg-accent/60",
      )}
    >
      <td className={cn("px-3 py-1 font-mono text-[11px] uppercase", severityClass(r.severity))}>
        {r.severity}
      </td>
      <td className="px-3 py-1 font-mono text-[11px] uppercase tracking-widest text-muted-foreground">
        {r.sources.join(" + ")}
      </td>
      <td className="px-3 py-1 font-mono text-[11px] text-foreground">{r.code}</td>
      <td className="px-3 py-1 text-muted-foreground">{r.message}</td>
      <td className="px-3 py-1 font-mono text-[11px] text-muted-foreground">
        {targetsLabel(blueprint, r)}
      </td>
    </tr>
  );

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-border px-3 py-1.5">
        <div className="flex items-center gap-1.5">
          <span className="hud-label">
            {rows.length} problem{rows.length === 1 ? "" : "s"}
          </span>
          {chip("all", `all ${rows.length}`, "")}
          {chip("error", `err ${counts.errors}`, "text-status-bad")}
          {chip("warning", `warn ${counts.warnings}`, "text-status-warn")}
          {chip("info", `info ${counts.infos}`, "text-status-info")}
          <button
            onClick={() => setGrouped((g) => !g)}
            className={cn(
              "rounded-sm border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-widest",
              grouped ? "border-primary text-foreground" : "border-border text-muted-foreground",
            )}
          >
            group by code
          </button>
        </div>
        <div className="flex items-center gap-2">
          <span className="font-mono text-[10px] text-muted-foreground">
            {hilmir.error
              ? hilmir.error
              : hilmir.stale
                ? "hilmir · stale, blueprint changed — re-run Validate"
                : hilmir.report
                  ? `${hilmir.report.validator}${hilmir.report.version ? ` ${hilmir.report.version}` : ""} · ${new Date(hilmir.report.checkedAt).toLocaleTimeString()}`
                  : `hilmir ${hilmir.mode}${hilmir.baseUrl ? ` · ${hilmir.baseUrl}` : ""} · not run`}
          </span>
          <button
            disabled={hilmir.running}
            onClick={() => void validateWithHilmir(blueprint)}
            className="inline-flex h-6 items-center gap-1 rounded-sm border border-border px-2 font-mono text-[10px] text-foreground hover:border-primary disabled:opacity-40"
          >
            {hilmir.running ? "Validating…" : "Validate with Hilmir"}
          </button>
        </div>
      </div>
      {shown.length === 0 ? (
        <div className="flex flex-1 items-center justify-center">
          <div className="text-center">
            <div className="hud-label">No problems</div>
            <p className="mt-1 text-xs text-muted-foreground">
              {rows.length === 0 ? "This blueprint validates clean." : "Nothing at this severity."}
            </p>
          </div>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-auto">
          <table className="w-full border-collapse text-[12px]">
            <thead className="sticky top-0 bg-card">
              <tr className="border-b border-border text-left">
                <th className="hud-label px-3 py-1.5">Severity</th>
                <th className="hud-label px-3 py-1.5">Source</th>
                <th className="hud-label px-3 py-1.5">Code</th>
                <th className="hud-label px-3 py-1.5">Message</th>
                <th className="hud-label px-3 py-1.5">Target</th>
              </tr>
            </thead>
            <tbody>
              {grouped
                ? groups.map(([code, list]) => (
                    <Fragment key={code}>
                      <tr className="bg-secondary/40">
                        <td
                          colSpan={5}
                          className="px-3 py-1 font-mono text-[10px] uppercase tracking-widest text-muted-foreground"
                        >
                          {code} · {list.length}
                        </td>
                      </tr>
                      {list.map(row)}
                    </Fragment>
                  ))
                : shown.map(row)}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
