import { create } from "zustand";
import type { Tenant } from "@/types";
import { tenantsRepo } from "@/repositories";

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
