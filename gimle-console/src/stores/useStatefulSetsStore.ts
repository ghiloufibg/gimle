import { create } from "zustand";
import type { StatefulSet, StatefulSetSpecInput } from "@/types";
import { statefulSetsRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: StatefulSet[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  create(spec: StatefulSetSpecInput): Promise<StatefulSet>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<StatefulSet>;
}

export const useStatefulSetsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await statefulSetsRepo.fetchPage({ cursor: null, pageSize: PAGE });
      set({
        items: p.items,
        nextCursor: p.nextCursor,
        hasMore: p.nextCursor !== null,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async loadMore() {
    const { loading, hasMore, nextCursor, items } = get();
    if (loading || !hasMore) return;
    set({ loading: true });
    try {
      const p = await statefulSetsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
      set({
        items: [...items, ...p.items],
        nextCursor: p.nextCursor,
        hasMore: p.nextCursor !== null,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ items: [], nextCursor: null, hasMore: true });
    await get().loadFirstPage();
  },
  async create(spec) {
    const s = await statefulSetsRepo.create(spec);
    set({ items: [s, ...get().items] });
    return s;
  },
  async remove(name) {
    await statefulSetsRepo.remove(name);
    set({ items: get().items.filter((s) => s.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((s) => s.spec.name === name);
    if (existing) return existing;
    const s = await statefulSetsRepo.fetchOne(name);
    set({ items: [...get().items, s] });
    return s;
  },
}));
