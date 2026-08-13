import { create } from "zustand";
import type { ConfigEntry } from "@/types";
import { configRepo } from "@/repositories";

const PAGE = 50;

interface State {
  tenantId: string | null;
  items: ConfigEntry[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  setTenant(id: string | null): void;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  upsert(entry: ConfigEntry): Promise<void>;
  remove(key: string): Promise<void>;
}

export const useConfigStore = create<State>((set, get) => ({
  tenantId: null,
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  setTenant(id) {
    set({ tenantId: id, items: [], nextCursor: null, hasMore: true, error: null });
  },
  async loadFirstPage() {
    const { tenantId } = get();
    if (!tenantId || get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await configRepo.fetchPage({ tenantId, cursor: null, pageSize: PAGE });
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
    const { tenantId, loading, hasMore, nextCursor, items } = get();
    if (!tenantId || loading || !hasMore) return;
    set({ loading: true });
    try {
      const p = await configRepo.fetchPage({ tenantId, cursor: nextCursor, pageSize: PAGE });
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
  async upsert(entry) {
    const saved = await configRepo.upsert(entry);
    const list = get().items;
    const i = list.findIndex((e) => e.key === saved.key);
    set({ items: i >= 0 ? list.map((e, ix) => (ix === i ? saved : e)) : [...list, saved] });
  },
  async remove(key) {
    const { tenantId } = get();
    if (!tenantId) return;
    await configRepo.remove(tenantId, key);
    set({ items: get().items.filter((e) => e.key !== key) });
  },
}));
