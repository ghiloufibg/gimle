import { create } from "zustand";
import type { Node } from "@/types";
import { nodesRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: Node[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  getOrFetch(nodeId: string): Promise<Node>;
}

export const useNodesStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await nodesRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await nodesRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
  async getOrFetch(nodeId) {
    const existing = get().items.find((n) => n.nodeId === nodeId);
    if (existing) return existing;
    const n = await nodesRepo.fetchOne(nodeId);
    set({ items: [...get().items, n] });
    return n;
  },
}));
