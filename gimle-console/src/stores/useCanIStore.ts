import { create } from "zustand";
import { canIRepo } from "@/repositories";
import type { ResourceKind, Verb } from "@/types";
import { useAuthStore } from "./useAuthStore";

function cacheKey(resource: ResourceKind, verb: Verb, tenant?: string): string {
  return `${resource}:${verb}:${tenant ?? ""}`;
}

interface State {
  /** `undefined` means "not yet asked" or "still in flight" -- consumers treat that the same as
   * an explicit `false`, the safe default for a question this session hasn't actually confirmed. */
  results: Record<string, boolean>;
  check(resource: ResourceKind, verb: Verb, tenant?: string): void;
  clear(): void;
}

// Not store state: nothing renders off "is this key in flight," so tracking it outside Zustand
// avoids a mutation that would need its own set() to stay consistent for no consumer's benefit.
const pending = new Set<string>();

/**
 * Caches `/authz/can-i` answers for the lifetime of the current session -- a screen with several
 * gated controls (a tenant detail page's Delete and Save quota, say) asks each question at most
 * once, not once per control render.
 */
export const useCanIStore = create<State>((set, get) => ({
  results: {},
  check(resource, verb, tenant) {
    const key = cacheKey(resource, verb, tenant);
    if (key in get().results || pending.has(key)) return;
    pending.add(key);
    canIRepo
      .check(resource, verb, tenant)
      .then((allowed) => {
        set((s) => ({ results: { ...s.results, [key]: allowed } }));
      })
      .catch(() => {
        // Unreachable/errored: leave unresolved rather than caching a guess -- the next mount
        // that asks the same question retries instead of being stuck with a wrong answer.
      })
      .finally(() => {
        pending.delete(key);
      });
  },
  clear() {
    set({ results: {} });
    pending.clear();
  },
}));

// A sign-out/sign-in as a different operator must never serve a permission answer cached for the
// previous one -- the store has no other signal that the principal changed underneath it.
useAuthStore.subscribe((state, prev) => {
  if (state.principal?.username !== prev.principal?.username) {
    useCanIStore.getState().clear();
  }
});
