import { create } from "zustand";
import type { ControllerRevision, StatefulSet, StatefulSetSpecInput } from "@/types";
import { statefulSetsRepo } from "@/repositories";

const PAGE = 20;

interface State {
  items: StatefulSet[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  revisions: ControllerRevision[];
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  create(spec: StatefulSetSpecInput): Promise<StatefulSet>;
  remove(name: string): Promise<void>;
  getOrFetch(name: string): Promise<StatefulSet>;
  loadRevisions(name: string): Promise<void>;
  rollback(name: string, toRevision?: number): Promise<void>;
}

export const useStatefulSetsStore = create<State>((set, get) => ({
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
      const p = await statefulSetsRepo.fetchPage({ cursor: null, pageSize: PAGE });
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
      const p = await statefulSetsRepo.fetchPage({ cursor: nextCursor, pageSize: PAGE });
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
    const s = await statefulSetsRepo.create(spec);
    set({ items: [s, ...get().items] });
    return s;
  },
  async remove(name) {
    // The item is already loaded (this screen is always reached from the already-fetched list/
    // detail state), so its own tenantId is looked up here rather than widening this method's
    // public signature -- every UI call site keeps calling remove(name) unchanged.
    const tenantId = get().items.find((s) => s.spec.name === name)?.spec.tenantId;
    await statefulSetsRepo.remove(name, tenantId);
    set({ items: get().items.filter((s) => s.spec.name !== name) });
  },
  async getOrFetch(name) {
    const existing = get().items.find((s) => s.spec.name === name);
    if (existing) return existing;
    const s = await statefulSetsRepo.fetchOne(name);
    set({ items: [...get().items, s] });
    return s;
  },
  async loadRevisions(name) {
    const tenantId = get().items.find((s) => s.spec.name === name)?.spec.tenantId;
    try {
      const revisions = await statefulSetsRepo.fetchRevisions(name, tenantId);
      set({ revisions });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async rollback(name, toRevision) {
    const tenantId = get().items.find((s) => s.spec.name === name)?.spec.tenantId;
    try {
      await statefulSetsRepo.rollback(name, toRevision, tenantId);
      const [s, revisions] = await Promise.all([
        statefulSetsRepo.fetchOne(name),
        statefulSetsRepo.fetchRevisions(name, tenantId),
      ]);
      set({
        items: get().items.map((x) => (x.spec.name === name ? s : x)),
        revisions,
      });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
}));
