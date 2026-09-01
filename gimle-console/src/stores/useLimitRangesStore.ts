import { create } from "zustand";
import type { LimitRange } from "@/types";
import { limitRangesRepo } from "@/repositories";

interface State {
  items: LimitRange[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  fetchOne(tenantId: string): Promise<LimitRange>;
  save(spec: LimitRange): Promise<void>;
  remove(tenantId: string): Promise<void>;
}

export const useLimitRangesStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await limitRangesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  // Deliberately not cached into `items`: this is the single-resource read the edit form starts
  // from, so it has to reflect the stored spec now rather than whatever the list held when it was
  // last fetched. It rethrows instead of setting `error` -- the caller is opening one row for
  // editing and wants that row's own failure, not a banner over the whole screen.
  async fetchOne(tenantId) {
    return limitRangesRepo.fetchOne(tenantId);
  },
  async save(spec) {
    set({ loading: true, error: null });
    try {
      await limitRangesRepo.save(spec);
      const items = await limitRangesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(tenantId) {
    set({ loading: true, error: null });
    try {
      await limitRangesRepo.remove(tenantId);
      set({ items: get().items.filter((r) => r.tenantId !== tenantId), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
