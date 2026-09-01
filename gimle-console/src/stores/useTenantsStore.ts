import { create } from "zustand";
import type { Tenant } from "@/types";
import { tenantsRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

const PAGE = 20;

interface State {
  items: Tenant[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  updateQuota(id: string, quota: Tenant["quota"]): Promise<void>;
  remove(id: string): Promise<void>;
  getOrFetch(id: string): Promise<Tenant>;
}

export const useTenantsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await tenantsRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await tenantsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
      const p = await tenantsRepo.fetchPage({
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
  async updateQuota(id, quota) {
    const t = await tenantsRepo.updateQuota(id, quota);
    set({ items: get().items.map((x) => (x.id === id ? t : x)) });
  },
  async remove(id) {
    await tenantsRepo.remove(id);
    set({ items: get().items.filter((x) => x.id !== id) });
  },
  async getOrFetch(id) {
    const existing = get().items.find((t) => t.id === id);
    if (existing) return existing;
    const t = await tenantsRepo.fetchOne(id);
    set({ items: [...get().items, t] });
    return t;
  },
}));
