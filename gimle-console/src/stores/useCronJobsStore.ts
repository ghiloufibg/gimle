import { create } from "zustand";
import type { CronJob, CronJobSpecInput } from "@/types";
import { cronJobsRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: CronJob[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  create(spec: CronJobSpecInput): Promise<CronJob>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<CronJob>;
  trigger(name: string): Promise<string>;
}

export const useCronJobsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await cronJobsRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await cronJobsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
    const c = await cronJobsRepo.create(spec);
    set({ items: [c, ...get().items] });
    return c;
  },
  async remove(name) {
    await cronJobsRepo.remove(name);
    set({ items: get().items.filter((c) => c.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((c) => c.spec.name === name);
    if (existing) return existing;
    const c = await cronJobsRepo.fetchOne(name);
    set({ items: [...get().items, c] });
    return c;
  },
  async trigger(name) {
    const jobName = await cronJobsRepo.trigger(name);
    // The triggered firing may have changed lastScheduleTime-adjacent state server-side (none
    // today, but re-fetching is cheap and keeps this store from silently going stale either way).
    const refreshed = await cronJobsRepo.fetchOne(name);
    set({ items: get().items.map((c) => (c.spec.name === name ? refreshed : c)) });
    return jobName;
  },
}));
