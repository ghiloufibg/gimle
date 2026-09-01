import { create } from "zustand";
import type { ModuleInstance } from "@/types";
import { instancesRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";
import type { InstancesFilter } from "@/repositories/instances";

const PAGE = 30;

interface State {
  items: ModuleInstance[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  filter: InstancesFilter;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  setFilter(filter: InstancesFilter): Promise<void>;
  getOrFetch(deploymentName: string, instanceIndex: number): Promise<ModuleInstance>;
}

function sameFilter(a: InstancesFilter, b: InstancesFilter) {
  return (
    a.deployment === b.deployment &&
    a.nodeId === b.nodeId &&
    a.tenantId === b.tenantId &&
    a.lifecycle === b.lifecycle
  );
}

export const useInstancesStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  filter: {},
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await instancesRepo.fetchPage({
        cursor: null,
        pageSize: PAGE,
        filter: get().filter,
      });
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
    const { loading, hasMore, nextCursor, items, filter } = get();
    if (loading || !hasMore) return;
    set({ loading: true });
    try {
      const p = await instancesRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE, filter });
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
      const p = await instancesRepo.fetchPage({
        cursor: null,
        pageSize: Math.max(PAGE, get().items.length),
        filter: get().filter,
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
  async setFilter(filter) {
    if (sameFilter(get().filter, filter)) return;
    set({ filter, items: [], nextCursor: null, hasMore: true, loading: false });
    await get().loadFirstPage();
  },
  async getOrFetch(deploymentName, instanceIndex) {
    const existing = get().items.find(
      (x) => x.deploymentName === deploymentName && x.instanceIndex === instanceIndex,
    );
    if (existing) return existing;
    const row = await instancesRepo.fetchOne(deploymentName, instanceIndex);
    set({ items: [...get().items, row] });
    return row;
  },
}));
