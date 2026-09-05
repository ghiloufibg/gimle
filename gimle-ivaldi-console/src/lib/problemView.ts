import type { Problem, Severity } from "./blueprint";

export type ProblemSource = "ivaldi" | "hilmir";

/** One fault, however many nodes or validators reported it. */
export interface ProblemRow {
  key: string;
  code: string;
  severity: Severity;
  message: string;
  nodeIds: string[];
  sources: ProblemSource[];
  file?: string;
}

/**
 * Drops faults that are only a consequence of a bigger one already on the list,
 * so the drawer shows the cause instead of the cascade.
 */
export function suppressCascades(problems: Problem[]): Problem[] {
  const codes = new Set(problems.map((p) => p.code));
  return problems.filter((p) => {
    if (codes.has("NO_MACHINES") && p.code === "UNKNOWN_MACHINE") return false;
    if (codes.has("NO_AGENTS") && p.code === "REQUIRED_LABEL_UNMATCHED") return false;
    if (
      p.code === "QUOTA_EXCEEDED" &&
      problems.some((q) => q.code === "QUOTA_NOT_POSITIVE" && q.nodeId === p.nodeId)
    )
      return false;
    return true;
  });
}

const SEVERITY_RANK: Record<Severity, number> = { error: 0, warning: 1, info: 2 };

/**
 * Collapses identical faults into one row: a port clash reported against both roles ends up as
 * one line naming both, rather than two lines saying the same thing.
 *
 * <p>Identity is the whole finding -- severity, code, message and file -- not the code alone. Two
 * findings sharing a code are usually two genuinely different faults (two different unknown
 * machines, two different quota breaches), and merging those would hide one of them. The same
 * fault found by both validators merges only when they word it identically, which they often do
 * not: the tiers are two implementations of the same rules, and where their wording differs the
 * row is shown twice rather than one of them being silently dropped.
 */
export function collapseProblems(ivaldi: Problem[], hilmir: Problem[]): ProblemRow[] {
  const rows = new Map<string, ProblemRow>();
  const add = (p: Problem, source: ProblemSource) => {
    const key = `${p.severity}|${p.code}|${p.message}|${p.file ?? ""}`;
    const row = rows.get(key);
    if (!row) {
      rows.set(key, {
        key,
        code: p.code,
        severity: p.severity,
        message: p.message,
        nodeIds: p.nodeId ? [p.nodeId] : [],
        sources: [source],
        file: p.file,
      });
      return;
    }
    if (p.nodeId && !row.nodeIds.includes(p.nodeId)) row.nodeIds.push(p.nodeId);
    if (!row.sources.includes(source)) row.sources.push(source);
  };
  for (const p of suppressCascades(ivaldi)) add(p, "ivaldi");
  for (const p of suppressCascades(hilmir)) add(p, "hilmir");
  return [...rows.values()].sort(
    (a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || a.code.localeCompare(b.code),
  );
}

export function tally(rows: ProblemRow[]): { errors: number; warnings: number; infos: number } {
  return {
    errors: rows.filter((r) => r.severity === "error").length,
    warnings: rows.filter((r) => r.severity === "warning").length,
    infos: rows.filter((r) => r.severity === "info").length,
  };
}
