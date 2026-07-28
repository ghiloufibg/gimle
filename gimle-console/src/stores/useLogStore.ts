import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { LogLine, LogTarget } from "@/types";
import { logsRepo } from "@/repositories";

const PAGE = 60;
const MAX_LINES = 1000;

export interface LogTailState {
  lines: LogLine[];
  olderCursor: string | null;
  following: boolean;
  loading: boolean;
  error: string | null;
  loadFirstPage(): Promise<void>;
  loadOlder(): Promise<void>;
  follow(): void;
  unfollow(): void;
}

function targetKey(t: LogTarget): string {
  if (t.kind === "instance") return `instance:${t.deploymentName}#${t.instanceIndex}:${t.category}`;
  if (t.kind === "node") return `node:${t.nodeId}:${t.category}`;
  return `controlplane::${t.category}`;
}

const registry = new Map<string, UseBoundStore<StoreApi<LogTailState>>>();

export function useLogStore(target: LogTarget): UseBoundStore<StoreApi<LogTailState>> {
  const key = targetKey(target);
  let store = registry.get(key);
  if (!store) {
    let stopFollow: (() => void) | null = null;
    store = create<LogTailState>((set, get) => ({
      lines: [],
      olderCursor: null,
      following: false,
      loading: false,
      error: null,
      async loadFirstPage() {
        if (get().loading) return;
        set({ loading: true, error: null });
        try {
          const p = await logsRepo.fetchPage({ target, cursor: null, pageSize: PAGE });
          set({ lines: p.items, olderCursor: p.nextCursor, loading: false });
        } catch (e) {
          set({ loading: false, error: (e as Error).message });
        }
      },
      async loadOlder() {
        if (get().loading) return;
        set({ loading: true });
        try {
          const p = await logsRepo.fetchPage({
            target,
            cursor: get().olderCursor,
            pageSize: PAGE,
          });
          set({ lines: [...p.items, ...get().lines], olderCursor: p.nextCursor, loading: false });
        } catch (e) {
          set({ loading: false, error: (e as Error).message });
        }
      },
      follow() {
        if (get().following) return;
        set({ following: true });
        stopFollow = logsRepo.openFollow(target, (line) => {
          const next = [...get().lines, line];
          if (next.length > MAX_LINES) next.splice(0, next.length - MAX_LINES);
          set({ lines: next });
        });
      },
      unfollow() {
        if (stopFollow) stopFollow();
        stopFollow = null;
        set({ following: false });
      },
    }));
    registry.set(key, store);
  }
  return store;
}
