import { create } from "zustand";
import type { NetworkPolicy } from "@/types";
import { networkPoliciesRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

interface State {
  items: NetworkPolicy[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
  save(spec: NetworkPolicy): Promise<void>;
  remove(name: string): Promise<void>;
}

export const useNetworkPoliciesStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await networkPoliciesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  /** The screen's auto-refresh read: no `loading` flag, so nothing on the screen flickers or
   * disables while a poll is out, and the last good list stays visible if one fails. */
  async poll() {
    if (get().loading) return;
    try {
      const items = await networkPoliciesRepo.fetchAll();
      set({ items, loaded: true, error: null });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
  async save(spec) {
    set({ loading: true, error: null });
    try {
      await networkPoliciesRepo.save(spec);
      const items = await networkPoliciesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(name) {
    // Unlike the other six tenant-scoped-by-name resources, tenantId here is never optional --
    // NetworkPolicySpec always requires one, and the item is already loaded (this screen is
    // always reached from the already-fetched list), so it's looked up here rather than widening
    // this method's own public signature.
    const tenantId = get().items.find((p) => p.name === name)?.tenantId;
    if (!tenantId) {
      throw new Error(`Cannot remove network policy "${name}": no known tenantId`);
    }
    set({ loading: true, error: null });
    try {
      await networkPoliciesRepo.remove(name, tenantId);
      set({ items: get().items.filter((p) => p.name !== name), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
