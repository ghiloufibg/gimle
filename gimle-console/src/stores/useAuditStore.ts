import { create } from "zustand";
import type { AuditEvent, AuditFilter, AuditPageStatus, AuditTrailStatus } from "@/types";
import { auditRepo } from "@/repositories";

const DEFAULT_LIMIT = 100;

interface State {
  items: AuditEvent[];
  status: AuditTrailStatus | null;
  page: AuditPageStatus | null;
  filter: AuditFilter;
  loading: boolean;
  loadingMore: boolean;
  loaded: boolean;
  error: string | null;
  setFilter(patch: Partial<AuditFilter>): void;
  search(): Promise<void>;
  loadMore(): Promise<void>;
  reset(): Promise<void>;
}

const byNewestFirst = (events: AuditEvent[]) =>
  [...events].sort((a, b) => b.occurredAtEpochMilli - a.occurredAtEpochMilli);

export const useAuditStore = create<State>((set, get) => ({
  items: [],
  status: null,
  page: null,
  filter: { limit: DEFAULT_LIMIT },
  loading: false,
  loadingMore: false,
  loaded: false,
  error: null,
  setFilter(patch) {
    const next = { ...get().filter, ...patch };
    for (const k of Object.keys(next) as Array<keyof AuditFilter>) {
      if (next[k] === "" || next[k] === undefined) delete next[k];
    }
    if (!next.limit) next.limit = DEFAULT_LIMIT;
    // Any cursor already held was issued under the previous filters; the control plane refuses it
    // outright once they change, so drop it here rather than send a request that cannot succeed.
    set({ filter: next, page: null });
  },
  async search() {
    if (get().loading || get().loadingMore) return;
    set({ loading: true, error: null });
    try {
      const { events, matchedCount, nextCursor, cursorExpired, ...trail } = await auditRepo.query(
        get().filter,
      );
      set({
        items: byNewestFirst(events),
        status: trail,
        page: { matchedCount, nextCursor, cursorExpired },
        loading: false,
        loaded: true,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  /**
   * Appends the page immediately older than the last event already held. The cursor anchors on that
   * event's own identity, so decisions recorded while the operator reads never shift the next page.
   */
  async loadMore() {
    const cursor = get().page?.nextCursor;
    if (!cursor || get().loading || get().loadingMore) return;
    set({ loadingMore: true, error: null });
    try {
      const { events, matchedCount, nextCursor, cursorExpired, ...trail } = await auditRepo.query(
        get().filter,
        cursor,
      );
      set({
        items: [...get().items, ...byNewestFirst(events)],
        status: trail,
        page: { matchedCount, nextCursor, cursorExpired },
        loadingMore: false,
      });
    } catch (e) {
      set({ loadingMore: false, error: (e as Error).message });
    }
  },
  async reset() {
    set({ filter: { limit: DEFAULT_LIMIT }, items: [], status: null, page: null });
    await get().search();
  },
}));
