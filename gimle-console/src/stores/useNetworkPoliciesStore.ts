import { create } from "zustand";
import type { NetworkPolicy } from "@/types";
import { networkPoliciesRepo } from "@/repositories";

interface State {
  items: NetworkPolicy[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
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
    set({ loading: true, error: null });
    try {
      await networkPoliciesRepo.remove(name);
      set({ items: get().items.filter((p) => p.name !== name), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
