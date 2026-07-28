import { create } from "zustand";
import type { Deployment, DeploymentSpecInput } from "@/types";
import { deploymentsRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: Deployment[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  create(spec: DeploymentSpecInput): Promise<Deployment>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<Deployment>;
}

export const useDeploymentsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await deploymentsRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await deploymentsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
    const d = await deploymentsRepo.create(spec);
    set({ items: [d, ...get().items] });
    return d;
  },
  async remove(name) {
    await deploymentsRepo.remove(name);
    set({ items: get().items.filter((d) => d.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((d) => d.spec.name === name);
    if (existing) return existing;
    const d = await deploymentsRepo.fetchOne(name);
    set({ items: [...get().items, d] });
    return d;
  },
}));
