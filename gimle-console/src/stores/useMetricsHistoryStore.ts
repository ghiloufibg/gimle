import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { MetricsHistoryLine, ProcessTarget } from "@/types";
import { metricsHistoryRepo } from "@/repositories";

const LIMIT = 120;
const MAX_LINES = 4000;

export interface MetricsHistoryState {
  lines: MetricsHistoryLine[];
  olderCursor: string | null;
  live: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadOlder(): Promise<void>;
  startLive(): void;
  stopLive(): void;
}

export function metricsTargetKey(t: ProcessTarget): string {
  return `${t.processKind}:${t.processId}`;
}

const registry = new Map<string, UseBoundStore<StoreApi<MetricsHistoryState>>>();

function merge(prev: MetricsHistoryLine[], incoming: MetricsHistoryLine[]): MetricsHistoryLine[] {
  const seen = new Set(prev.map((l) => `${l.timestamp}|${l.name}`));
  const next = [...prev];
  for (const l of incoming) {
    const k = `${l.timestamp}|${l.name}`;
    if (seen.has(k)) continue;
    seen.add(k);
    next.push(l);
  }
  next.sort((a, b) => a.timestamp.localeCompare(b.timestamp));
  if (next.length > MAX_LINES) next.splice(0, next.length - MAX_LINES);
  return next;
}

/** One store instance per distinct ProcessTarget, the same keyed-registry pattern useLogStore
 * uses for LogTarget -- each process has its own independent metric history. */
export function useMetricsHistoryStore(
  target: ProcessTarget,
): UseBoundStore<StoreApi<MetricsHistoryState>> {
  const key = metricsTargetKey(target);
  let store = registry.get(key);
  if (!store) {
    let stopPoll: (() => void) | null = null;
    store = create<MetricsHistoryState>((set, get) => ({
      lines: [],
      olderCursor: null,
      live: false,
      loading: false,
      error: null,
      async loadFirstPage() {
        if (get().loading) return;
        set({ loading: true, error: null });
        try {
          const env = await metricsHistoryRepo.fetchPage({ target, cursor: null, limit: LIMIT });
          set({ lines: merge([], env.lines), olderCursor: env.olderCursor, loading: false });
        } catch (e) {
          set({ loading: false, error: (e as Error).message });
        }
      },
      async loadOlder() {
        if (get().loading) return;
        set({ loading: true });
        try {
          const env = await metricsHistoryRepo.fetchPage({
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
        stopPoll = metricsHistoryRepo.openPoll(target, (lines) => {
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
