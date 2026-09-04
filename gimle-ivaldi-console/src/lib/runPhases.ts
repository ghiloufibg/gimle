import type { RunStep } from "@/repositories/contracts";

/**
 * The real `gimle-ivaldi` backend reports a coarse `RunStatus` plus a plain-text log — no
 * per-step events. This derives the same five-step timeline the mock runner scripts, by
 * recognizing the exact log lines `RunController` itself writes (see `gimle-ivaldi`'s own
 * `run/RunController.java`): deterministic, since both ends of that text are ours, not a
 * heuristic guess at arbitrary log content.
 */
export type PhaseId = "validate" | "boot" | "seed" | "deploy" | "active";

const PHASES: { id: PhaseId; label: string }[] = [
  { id: "validate", label: "Validate topology" },
  { id: "boot", label: "Boot platform" },
  { id: "seed", label: "Push artifacts" },
  { id: "deploy", label: "Deploy bundle" },
  { id: "active", label: "Cluster running" },
];

/** A log-line substring that marks one phase as reached, and how. */
const MARKERS: { contains: string; phase: PhaseId; result: "ok" | "skipped" }[] = [
  { contains: "validated ", phase: "validate", result: "ok" },
  { contains: "booted ", phase: "boot", result: "ok" },
  { contains: "topology unchanged", phase: "boot", result: "skipped" },
  { contains: "no jar-sourced workloads", phase: "seed", result: "ok" },
  { contains: "pushed artifact ", phase: "seed", result: "ok" },
  { contains: "deployed fresh", phase: "deploy", result: "ok" },
  { contains: "upgraded (revision", phase: "deploy", result: "ok" },
  { contains: "run complete", phase: "active", result: "ok" },
];

export function initialSteps(): RunStep[] {
  return PHASES.map((p) => ({ id: p.id, label: p.label, status: "pending" }));
}

/** Marks the currently-in-progress phase "running", matching a live `RunStatus`. */
export function markCurrentPhase(steps: RunStep[], phase: PhaseId): RunStep[] {
  return steps.map((s) =>
    s.id === phase && s.status === "pending" ? { ...s, status: "running" } : s,
  );
}

/** Applies one incoming log line's marker, if it matches, leaving everything else untouched. */
export function applyLogLine(steps: RunStep[], line: string): RunStep[] {
  const marker = MARKERS.find((m) => line.includes(m.contains));
  if (!marker) return steps;
  return steps.map((s) => (s.id === marker.phase ? { ...s, status: marker.result } : s));
}

/** Once a run reaches a terminal state, resolves whatever no log line ever confirmed. */
export function finalizeSteps(steps: RunStep[], terminal: "running" | "failed"): RunStep[] {
  if (terminal === "running") {
    return steps.map((s) =>
      s.status === "pending" || s.status === "running" ? { ...s, status: "ok" } : s,
    );
  }
  let failedOneAlready = false;
  return steps.map((s) => {
    if (s.status === "ok" || s.status === "skipped") return s;
    if (!failedOneAlready) {
      failedOneAlready = true;
      return { ...s, status: "failed" };
    }
    return { ...s, status: "skipped" };
  });
}
