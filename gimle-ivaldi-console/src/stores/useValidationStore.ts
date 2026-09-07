import { create } from "zustand";

import type { Blueprint, Problem } from "@/lib/blueprint";
import { renderFiles } from "@/lib/render";
import { validate } from "@/lib/rules";
import { hilmirValidator, type HilmirFinding, type HilmirReport } from "@/repositories";

export type ProblemSource = "ivaldi" | "hilmir";

interface ValidationState {
  problems: Problem[];
  /** Findings reported by Hilmir itself, mapped onto Ivaldi's Problem shape. */
  serverProblems: Problem[];
  hilmir: {
    mode: "mock" | "http";
    baseUrl: string | null;
    report: HilmirReport | null;
    running: boolean;
    error: string | null;
    /** The last report described an older blueprint and has been dropped. */
    stale: boolean;
  };
  recompute: (blueprint: Blueprint | null) => void;
  validateWithHilmir: (blueprint: Blueprint) => Promise<void>;
  clearHilmir: () => void;
  allProblems: () => Problem[];
  sourceOf: (problem: Problem) => ProblemSource;
  problemsFor: (nodeId: string) => Problem[];
  errorCount: () => number;
  warningCount: () => number;
  infoCount: () => number;
}

/**
 * Resolves a Hilmir finding back onto a canvas node so it can be selected. Matching by name alone
 * (`finding.resource`, a bare `Kind/name` with no tenant in it) picked the first node of that name
 * on the whole canvas -- wrong the moment two tenants legally share a workload name. The finding's
 * own `file` names the exact manifest Hilmir was looking at when it fired, and every standalone
 * manifest file has exactly one owning node (`owners`, built alongside the very
 * {@link renderFiles} call whose output was sent to Hilmir -- see `validateWithHilmir` below), so
 * it is checked first; the name-only match survives only as a fallback for a finding naming no
 * file, or a `topology.yaml` finding, which spans every role/machine at once and so has no single
 * owning node to look up by file at all.
 */
function nodeIdFor(
  blueprint: Blueprint,
  finding: HilmirFinding,
  owners: Map<string, string>,
): string | undefined {
  if (finding.file) {
    const owner = owners.get(finding.file);
    if (owner) return owner;
  }
  const target = finding.resource?.includes("/")
    ? finding.resource.slice(finding.resource.indexOf("/") + 1)
    : finding.resource;
  if (!target) return undefined;
  const match = blueprint.nodes.find((n) => {
    const d = n.data as unknown as Record<string, unknown>;
    return d.name === target || d.nodeId === target || d.id === target;
  });
  return match?.id;
}

function toProblem(
  blueprint: Blueprint,
  finding: HilmirFinding,
  owners: Map<string, string>,
): Problem {
  const where = finding.path ? ` (${finding.path})` : "";
  return {
    code: finding.code,
    severity: finding.severity,
    message: `${finding.message}${where}`,
    nodeId: nodeIdFor(blueprint, finding, owners),
    file: finding.file,
  };
}

export const useValidationStore = create<ValidationState>((set, get) => ({
  problems: [],
  serverProblems: [],
  hilmir: {
    mode: hilmirValidator.mode,
    baseUrl: hilmirValidator.baseUrl,
    report: null,
    running: false,
    error: null,
    stale: false,
  },

  // Any blueprint change invalidates the last tier-2 answer: the stored server
  // findings describe a document that no longer exists, so they are dropped and
  // the footer says the report is stale until Validate runs again.
  recompute: (blueprint) =>
    set((s) => ({
      problems: blueprint ? validate(blueprint) : [],
      serverProblems: [],
      hilmir:
        s.serverProblems.length || s.hilmir.report
          ? { ...s.hilmir, report: null, stale: true }
          : s.hilmir,
    })),

  validateWithHilmir: async (blueprint) => {
    if (get().hilmir.running) return;
    set((s) => ({ hilmir: { ...s.hilmir, running: true, error: null } }));
    try {
      const rendered = renderFiles(blueprint);
      const files = rendered.map((f) => ({ path: f.path, content: f.content }));
      const owners = new Map(
        rendered.filter((f) => f.nodeId).map((f) => [f.path, f.nodeId as string]),
      );
      const report = await hilmirValidator.validate(files);
      set((s) => ({
        serverProblems: report.findings.map((f) => toProblem(blueprint, f, owners)),
        hilmir: { ...s.hilmir, report, running: false, error: report.error, stale: false },
      }));
    } catch (error) {
      set((s) => ({
        serverProblems: [],
        hilmir: {
          ...s.hilmir,
          running: false,
          stale: false,
          error: error instanceof Error ? error.message : "hilmir unreachable",
        },
      }));
    }
  },

  clearHilmir: () =>
    set((s) => ({
      serverProblems: [],
      hilmir: { ...s.hilmir, report: null, error: null, stale: false },
    })),

  allProblems: () => [...get().problems, ...get().serverProblems],
  sourceOf: (problem) => (get().serverProblems.includes(problem) ? "hilmir" : "ivaldi"),
  problemsFor: (nodeId) =>
    get()
      .allProblems()
      .filter((p) => p.nodeId === nodeId),
  errorCount: () =>
    get()
      .allProblems()
      .filter((p) => p.severity === "error").length,
  warningCount: () =>
    get()
      .allProblems()
      .filter((p) => p.severity === "warning").length,
  infoCount: () =>
    get()
      .allProblems()
      .filter((p) => p.severity === "info").length,
}));
