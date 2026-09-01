import { create } from "zustand";
import type { ControllerRevision, DaemonSet, DaemonSetSpecInput } from "@/types";
import { daemonSetsRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

const PAGE = 20;

interface State {
  items: DaemonSet[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  revisions: ControllerRevision[];
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  create(spec: DaemonSetSpecInput): Promise<DaemonSet>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<DaemonSet>;
  loadRevisions(name: string): Promise<void>;
  rollback(name: string, toRevision?: number): Promise<void>;
}

export const useDaemonSetsStore = create<State>((set, get) => ({
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  revisions: [],
  async loadFirstPage() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await daemonSetsRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await daemonSetsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
      const p = await daemonSetsRepo.fetchPage({
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
    const d = await daemonSetsRepo.create(spec);
    set({ items: [d, ...get().items] });
    return d;
  },
  async remove(name) {
    // The item is already loaded (this screen is always reached from the already-fetched list/
    // detail state), so its own tenantId is looked up here rather than widening this method's
    // public signature -- every UI call site keeps calling remove(name) unchanged.
    const tenantId = get().items.find((d) => d.spec.name === name)?.spec.tenantId;
    await daemonSetsRepo.remove(name, tenantId);
    set({ items: get().items.filter((d) => d.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((d) => d.spec.name === name);
    if (existing) return existing;
    const d = await daemonSetsRepo.fetchOne(name);
    set({ items: [...get().items, d] });
    return d;
  },
  async loadRevisions(name) {
    const tenantId = get().items.find((d) => d.spec.name === name)?.spec.tenantId;
    try {
      const revisions = await daemonSetsRepo.fetchRevisions(name, tenantId);
      set({ revisions });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async rollback(name, toRevision) {
    const tenantId = get().items.find((d) => d.spec.name === name)?.spec.tenantId;
    try {
      await daemonSetsRepo.rollback(name, toRevision, tenantId);
      const [d, revisions] = await Promise.all([
        daemonSetsRepo.fetchOne(name),
        daemonSetsRepo.fetchRevisions(name, tenantId),
      ]);
      set({
        items: get().items.map((x) => (x.spec.name === name ? d : x)),
        revisions,
      });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
}));
