import { create } from "zustand";
import type { RunDetail, TestResultEvent } from "@/domain/types";
import { runsRepository, type Unsubscribe } from "@/repositories";

interface RunDetailState {
  runId: string | null;
  detail: RunDetail | null;
  events: TestResultEvent[];
  loading: boolean;
  error: string | null;
  following: boolean;
  autoFollow: boolean;
  unsubscribe: Unsubscribe | null;
  load: (runId: string) => Promise<void>;
  follow: () => void;
  unfollow: () => void;
  setAutoFollow: (value: boolean) => void;
  reset: () => void;
}

export const useRunDetailStore = create<RunDetailState>((set, get) => ({
  runId: null,
  detail: null,
  events: [],
  loading: false,
  error: null,
  following: false,
  autoFollow: true,
  unsubscribe: null,
  load: async (runId) => {
    get().unfollow();
    set({ runId, loading: true, error: null, detail: null, events: [] });
    try {
      const [detail, events] = await Promise.all([
        runsRepository.getRun(runId),
        runsRepository.getRunEvents(runId),
      ]);
      if (get().runId !== runId) return;
      set({ detail, events, loading: false });
      if (detail.run.status === "live" && get().autoFollow) get().follow();
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "failed to load run" });
    }
  },
  follow: () => {
    const { runId, following, events } = get();
    if (!runId || following) return;
    const unsubscribe = runsRepository.followRunEvents(runId, events.length, (event) => {
      set((s) => {
        const events = [...s.events, event];
        if (!s.detail) return { events };
        const seen = new Set(events.map((e) => e.testId));
        const moduleProgress = s.detail.moduleProgress.map((m) => {
          const done = [...seen].filter((id) => id.startsWith(`${m.module}:`)).length;
          const failed = events.filter(
            (e) => e.testId.startsWith(`${m.module}:`) && e.outcome === "FAILED",
          ).length;
          return { ...m, completed: Math.min(done, m.total), failed };
        });
        return { events, detail: { ...s.detail, moduleProgress } };
      });
    });
    set({ unsubscribe, following: true });
  },
  unfollow: () => {
    const { unsubscribe } = get();
    unsubscribe?.();
    set({ unsubscribe: null, following: false });
  },
  setAutoFollow: (value) => {
    set({ autoFollow: value });
    if (value) get().follow();
    else get().unfollow();
  },
  reset: () => {
    get().unfollow();
    set({ runId: null, detail: null, events: [], error: null, loading: false });
  },
}));
