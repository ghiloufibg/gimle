import { create } from "zustand";
import type { Service, ServiceEndpoints } from "@/types";
import { servicesRepo } from "@/repositories";

interface State {
  items: Service[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  save(spec: Service): Promise<void>;
  remove(name: string): Promise<void>;
  fetchEndpoints(name: string): Promise<ServiceEndpoints>;
}

export const useServicesStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await servicesRepo.fetchAll();
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
      await servicesRepo.save(spec);
      const items = await servicesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(name) {
    set({ loading: true, error: null });
    try {
      await servicesRepo.remove(name);
      set({ items: get().items.filter((s) => s.name !== name), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  // Deliberately never cached alongside `items`: the live endpoint set is reconciler-independent
  // and can change between two reads of an otherwise-unchanged Service, the same reasoning
  // `ApiServer#handleServiceEndpoints` documents for never serving it from a cache.
  async fetchEndpoints(name) {
    return servicesRepo.fetchEndpoints(name);
  },
}));
