import { create } from "zustand";
import type { Job, JobSpecInput } from "@/types";
import { jobsRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: Job[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  create(spec: JobSpecInput): Promise<Job>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<Job>;
}

export const useJobsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await jobsRepo.fetchPage({ cursor: null, pageSize: PAGE });
      set({ items: p.items, nextCursor: p.nextCursor, hasMore: p.nextCursor !== null, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async loadMore() {
    const { loading, hasMore, nextCursor, items } = get();
    if (loading || !hasMore) return;
    set({ loading: true });
    try {
      const p = await jobsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
    const j = await jobsRepo.create(spec);
    set({ items: [j, ...get().items] });
    return j;
  },
  async remove(name) {
    await jobsRepo.remove(name);
    set({ items: get().items.filter((j) => j.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((j) => j.spec.name === name);
    if (existing) return existing;
    const j = await jobsRepo.fetchOne(name);
    set({ items: [...get().items, j] });
    return j;
  },
}));
