import { create } from "zustand";
import type { TestHistory } from "@/domain/types";
import { testHistoryRepository } from "@/repositories";

interface TestHistoryState {
  testId: string | null;
  history: TestHistory | null;
  loading: boolean;
  error: string | null;
  load: (testId: string) => Promise<void>;
}

export const useTestHistoryStore = create<TestHistoryState>((set, get) => ({
  testId: null,
  history: null,
  loading: false,
  error: null,
  load: async (testId) => {
    set({ testId, loading: true, error: null, history: null });
    try {
      const history = await testHistoryRepository.getHistory(testId);
      if (get().testId !== testId) return;
      set({ history, loading: false });
    } catch (e) {
      set({
        loading: false,
        error: e instanceof Error ? e.message : "failed to load test history",
      });
    }
  },
}));
