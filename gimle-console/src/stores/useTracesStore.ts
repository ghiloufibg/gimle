import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { ProcessTarget, TraceSpanLine } from "@/types";
import { tracesRepo } from "@/repositories";

const LIMIT = 60;
const MAX_LINES = 2000;

export interface TracesState {
  lines: TraceSpanLine[];
  olderCursor: string | null;
  live: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadOlder(): Promise<void>;
  startLive(): void;
  stopLive(): void;
}

export function tracesTargetKey(t: ProcessTarget): string {
  return `${t.processKind}:${t.processId}`;
}

const registry = new Map<string, UseBoundStore<StoreApi<TracesState>>>();

function merge(prev: TraceSpanLine[], incoming: TraceSpanLine[]): TraceSpanLine[] {
  const seen = new Set(prev.map((l) => l.spanId));
  const next = [...prev];
  for (const l of incoming) {
    if (seen.has(l.spanId)) continue;
    seen.add(l.spanId);
    next.push(l);
  }
  next.sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  if (next.length > MAX_LINES) next.length = MAX_LINES;
  return next;
}

/** One store instance per distinct ProcessTarget -- same keyed-registry pattern as
 * useMetricsHistoryStore/useLogStore. */
export function useTracesStore(target: ProcessTarget): UseBoundStore<StoreApi<TracesState>> {
  const key = tracesTargetKey(target);
  let store = registry.get(key);
  if (!store) {
    let stopPoll: (() => void) | null = null;
    store = create<TracesState>((set, get) => ({
      lines: [],
      olderCursor: null,
      live: false,
      loading: false,
      error: null,
      async loadFirstPage() {
        if (get().loading) return;
        set({ loading: true, error: null });
        try {
          const env = await tracesRepo.fetchPage({ target, cursor: null, limit: LIMIT });
          set({ lines: merge([], env.lines), olderCursor: env.olderCursor, loading: false });
        } catch (e) {
          set({ loading: false, error: (e as Error).message });
        }
      },
      async loadOlder() {
        if (get().loading) return;
        set({ loading: true });
        try {
          const env = await tracesRepo.fetchPage({
            target,
            cursor: get().olderCursor,
            limit: LIMIT,
          });
          set({
            lines: merge(get().lines, env.lines),
            olderCursor: env.olderCursor,
            loading: false,
          });
        } catch (e) {
          set({ loading: false, error: (e as Error).message });
        }
      },
      startLive() {
        if (get().live) return;
        set({ live: true });
        stopPoll = tracesRepo.openPoll(target, (lines) => {
          set({ lines: merge(get().lines, lines) });
        });
      },
      stopLive() {
        if (stopPoll) stopPoll();
        stopPoll = null;
        set({ live: false });
      },
    }));
    registry.set(key, store);
  }
  return store;
}
