import { create } from "zustand";
import type { CronJob, CronJobSpecInput } from "@/types";
import { cronJobsRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

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
  poll(): Promise<void>;
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
  /** A background re-read for the screen's auto-refresh, deliberately not `refresh()`: it never
   * blanks the table first, never touches `loading` (so no control flickers disabled under the
   * pointer and no "Loading…" placeholder replaces rows that are already there), and asks for as
   * many rows as are already on screen so pages the operator paged in are not silently dropped
   * every tick. */
  async poll() {
    if (get().loading) return;
    try {
      const p = await cronJobsRepo.fetchPage({
        cursor: null,
        pageSize: Math.max(PAGE, get().items.length),
      });
      set({
        items: p.items,
        nextCursor: p.nextCursor,
        hasMore: p.nextCursor !== null,
        error: null,
      });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
  async create(spec) {
    const c = await cronJobsRepo.create(spec);
    set({ items: [c, ...get().items] });
    return c;
  },
  async remove(name) {
    // The item is already loaded (this screen is always reached from the already-fetched list/
    // detail state), so its own tenantId is looked up here rather than widening this method's
    // public signature -- every UI call site keeps calling remove(name) unchanged.
    const tenantId = get().items.find((c) => c.spec.name === name)?.spec.tenantId;
    await cronJobsRepo.remove(name, tenantId);
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
