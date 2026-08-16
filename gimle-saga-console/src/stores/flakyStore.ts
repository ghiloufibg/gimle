import { create } from "zustand";
import type { FlakyBoard, FlakyFilter } from "@/domain/types";
import { flakyRepository } from "@/repositories";

interface FlakyState {
  board: FlakyBoard | null;
  filter: FlakyFilter;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
  setFilter: (patch: Partial<FlakyFilter>) => void;
}

export const useFlakyStore = create<FlakyState>((set, get) => ({
  board: null,
  filter: { module: "all", window: 30, quarantinedOnly: false },
  loading: false,
  error: null,
  load: async () => {
    set({ loading: true, error: null });
    const filter = get().filter;
    try {
      const board = await flakyRepository.getBoard(filter);
      if (get().filter !== filter) return;
      set({ board, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : "failed to load scoreboard" });
    }
  },
  setFilter: (patch) => {
    set({ filter: { ...get().filter, ...patch } });
    void get().load();
  },
}));
