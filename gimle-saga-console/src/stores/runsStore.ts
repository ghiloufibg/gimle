import { create } from "zustand";
import type { Run } from "@/domain/types";
import { runsRepository } from "@/repositories";

interface RunsState {
  runs: Run[];
  loading: boolean;
  error: string | null;
  query: string;
  loaded: boolean;
  load: () => Promise<void>;
  setQuery: (query: string) => void;
}

export const useRunsStore = create<RunsState>((set, get) => ({
  runs: [],
  loading: false,
  error: null,
  query: "",
  loaded: false,
  load: async () => {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const runs = await runsRepository.listRuns();
      set({ runs, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "failed to load runs" });
    }
  },
  setQuery: (query) => set({ query }),
}));
