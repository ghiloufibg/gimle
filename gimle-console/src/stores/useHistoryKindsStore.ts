import { create } from "zustand";

import { metricsHistoryRepo, tracesRepo } from "@/repositories";
import type { ProcessKind } from "@/types";

/** Which history surface a picker is choosing a process for. */
export type HistorySignal = "metrics" | "traces";

/**
 * Every kind this console knows how to build a processId for, in the order the picker offers them.
 * Only a fallback: it is what a picker shows when the control plane could not be asked which kinds
 * ship a signal, so the operator still has a usable picker rather than an empty row. Offering a
 * kind that ships nothing costs one empty screen; offering none costs the screen entirely.
 */
export const ALL_PROCESS_KINDS: ProcessKind[] = [
  "CONTROLPLANE",
  "FAFNIR",
  "STORE",
  "AGENT",
  "WORKER",
  "SKALD",
  "ANDVARI",
];

/**
 * The process kinds that genuinely ship metrics, and those that genuinely ship spans -- two
 * different sets, read once each from the control plane's own `GET /metrics-history` and `GET
 * /traces-history`. Several process kinds ship metrics while starting no spans at all, so a single
 * shared list would offer trace histories that can only ever come back empty.
 */
interface HistoryKindsState {
  /** `null` until answered for that signal (or if the read failed -- see `kindsFor`). */
  metrics: ProcessKind[] | null;
  traces: ProcessKind[] | null;
  load(signal: HistorySignal): Promise<void>;
  /** The kinds to offer for `signal`, falling back to every known kind while unanswered. */
  kindsFor(signal: HistorySignal): ProcessKind[];
}

export const useHistoryKindsStore = create<HistoryKindsState>((set, get) => ({
  metrics: null,
  traces: null,
  async load(signal) {
    if (get()[signal] !== null) return;
    try {
      const kinds =
        signal === "metrics"
          ? await metricsHistoryRepo.fetchProcessKinds()
          : await tracesRepo.fetchProcessKinds();
      // An answer naming nothing at all is not usable as a picker; keep the fallback instead.
      if (kinds.length > 0) set({ [signal]: kinds } as Pick<HistoryKindsState, HistorySignal>);
    } catch {
      // Unreachable or unauthorized: the picker keeps its fallback, same posture as useAddonsStore.
    }
  },
  kindsFor(signal) {
    return get()[signal] ?? ALL_PROCESS_KINDS;
  },
}));
