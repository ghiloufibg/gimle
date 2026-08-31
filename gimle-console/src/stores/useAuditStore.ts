import { create } from "zustand";
import type { AuditEvent, AuditFilter, AuditTrailStatus } from "@/types";
import { auditRepo } from "@/repositories";

const DEFAULT_LIMIT = 100;

interface State {
  items: AuditEvent[];
  status: AuditTrailStatus | null;
  filter: AuditFilter;
  loading: boolean;
  loaded: boolean;
  error: string | null;
  setFilter(patch: Partial<AuditFilter>): void;
  search(): Promise<void>;
  reset(): Promise<void>;
}

export const useAuditStore = create<State>((set, get) => ({
  items: [],
  status: null,
  filter: { limit: DEFAULT_LIMIT },
  loading: false,
  loaded: false,
  error: null,
  setFilter(patch) {
    const next = { ...get().filter, ...patch };
    for (const k of Object.keys(next) as Array<keyof AuditFilter>) {
      if (next[k] === "" || next[k] === undefined) delete next[k];
    }
    if (!next.limit) next.limit = DEFAULT_LIMIT;
    set({ filter: next });
  },
  async search() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const { events, ...status } = await auditRepo.query(get().filter);
      set({
        items: [...events].sort((a, b) => b.occurredAtEpochMilli - a.occurredAtEpochMilli),
        status,
        loading: false,
        loaded: true,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async reset() {
    set({ filter: { limit: DEFAULT_LIMIT }, items: [], status: null });
    await get().search();
  },
}));
