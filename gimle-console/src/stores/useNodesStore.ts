import { create } from "zustand";
import type { Node } from "@/types";
import { nodesRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

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
  poll(): Promise<void>;
  getOrFetch(nodeId: string): Promise<Node>;
  setCordoned(nodeId: string, cordoned: boolean): Promise<void>;
  setTaint(nodeId: string, tenantId: string, tainted: boolean): Promise<void>;
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
  /** A background re-read for the screen's auto-refresh, deliberately not `refresh()`: it never
   * blanks the table first, never touches `loading` (so no control flickers disabled under the
   * pointer and no "Loading…" placeholder replaces rows that are already there), and asks for as
   * many rows as are already on screen so pages the operator paged in are not silently dropped
   * every tick. */
  async poll() {
    if (get().loading) return;
    try {
      const p = await nodesRepo.fetchPage({
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
  async getOrFetch(nodeId) {
    const existing = get().items.find((n) => n.nodeId === nodeId);
    if (existing) return existing;
    const n = await nodesRepo.fetchOne(nodeId);
    set({ items: [...get().items, n] });
    return n;
  },
  async setCordoned(nodeId, cordoned) {
    try {
      await nodesRepo.setCordoned(nodeId, cordoned);
      set({
        items: get().items.map((n) => (n.nodeId === nodeId ? { ...n, cordoned } : n)),
      });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
  async setTaint(nodeId, tenantId, tainted) {
    try {
      await nodesRepo.setTaint(nodeId, tenantId, tainted);
      set({
        items: get().items.map((n) =>
          n.nodeId === nodeId
            ? {
                ...n,
                taints: tainted
                  ? [...new Set([...n.taints, tenantId])].sort()
                  : n.taints.filter((t) => t !== tenantId),
              }
            : n,
        ),
      });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
}));
