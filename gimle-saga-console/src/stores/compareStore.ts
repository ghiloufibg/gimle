import { create } from "zustand";
import type { CompareResult } from "@/domain/types";
import { runsRepository } from "@/repositories";

interface CompareState {
  baseRunId: string | null;
  headRunId: string | null;
  result: CompareResult | null;
  loading: boolean;
  error: string | null;
  setBase: (runId: string) => void;
  setHead: (runId: string) => void;
  load: () => Promise<void>;
}

export const useCompareStore = create<CompareState>((set, get) => ({
  baseRunId: null,
  headRunId: null,
  result: null,
  loading: false,
  error: null,
  setBase: (runId) => {
    set({ baseRunId: runId });
    if (get().headRunId) void get().load();
  },
  setHead: (runId) => {
    set({ headRunId: runId });
    if (get().baseRunId) void get().load();
  },
  load: async () => {
    const { baseRunId, headRunId } = get();
    if (!baseRunId || !headRunId) return;
    set({ loading: true, error: null });
    try {
      const result = await runsRepository.compareRuns(baseRunId, headRunId);
      set({ result, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "compare failed" });
    }
  },
}));
