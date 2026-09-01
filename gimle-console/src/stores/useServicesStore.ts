import { create } from "zustand";
import type { Service, ServiceEndpoints } from "@/types";
import { servicesRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

interface State {
  items: Service[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  poll(): Promise<void>;
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
  /** The screen's auto-refresh read: no `loading` flag, so nothing on the screen flickers or
   * disables while a poll is out, and the last good list stays visible if one fails. */
  async poll() {
    if (get().loading) return;
    try {
      const items = await servicesRepo.fetchAll();
      set({ items, loaded: true, error: null });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
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
    // The item is already loaded (this screen is always reached from the already-fetched list
    // state), so its own tenantId is looked up here rather than widening this method's public
    // signature -- every UI call site keeps calling remove(name) unchanged.
    const tenantId = get().items.find((s) => s.name === name)?.tenantId;
    set({ loading: true, error: null });
    try {
      await servicesRepo.remove(name, tenantId);
      set({ items: get().items.filter((s) => s.name !== name), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  // Deliberately never cached alongside `items`: the live endpoint set is reconciler-independent
  // and can change between two reads of an otherwise-unchanged Service, the same reasoning
  // `ApiServer#handleServiceEndpoints` documents for never serving it from a cache. tenantId is
  // looked up from the already-loaded `items` the same way `remove` does, so callers keep passing
  // just the name.
  async fetchEndpoints(name) {
    const tenantId = get().items.find((s) => s.name === name)?.tenantId;
    return servicesRepo.fetchEndpoints(name, tenantId);
  },
}));
