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

/** Resolves a Hilmir finding back onto a canvas node so it can be selected. */
function nodeIdFor(blueprint: Blueprint, finding: HilmirFinding): string | undefined {
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

function toProblem(blueprint: Blueprint, finding: HilmirFinding): Problem {
  const where = finding.path ? ` (${finding.path})` : "";
  return {
    code: finding.code,
    severity: finding.severity,
    message: `${finding.message}${where}`,
    nodeId: nodeIdFor(blueprint, finding),
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
  },

  recompute: (blueprint) => set({ problems: blueprint ? validate(blueprint) : [] }),

  validateWithHilmir: async (blueprint) => {
    if (get().hilmir.running) return;
    set((s) => ({ hilmir: { ...s.hilmir, running: true, error: null } }));
    const files = renderFiles(blueprint).map((f) => ({ path: f.path, content: f.content }));
    try {
      const report = await hilmirValidator.validate(files);
      set((s) => ({
        serverProblems: report.findings.map((f) => toProblem(blueprint, f)),
        hilmir: { ...s.hilmir, report, running: false, error: report.error },
      }));
    } catch (error) {
      set((s) => ({
        serverProblems: [],
        hilmir: {
          ...s.hilmir,
          running: false,
          error: error instanceof Error ? error.message : "hilmir unreachable",
        },
      }));
    }
  },

  clearHilmir: () =>
    set((s) => ({ serverProblems: [], hilmir: { ...s.hilmir, report: null, error: null } })),

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
