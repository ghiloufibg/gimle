import { create } from "zustand";
import type { ConfigMap } from "@/types";
import { configMapsRepo } from "@/repositories";
import { ConfigMapConflict } from "@/repositories/configmaps";

interface State {
  tenantId: string | null;
  names: string[];
  loading: boolean;
  error: string | null;
  // The ConfigMap currently open in the edit panel, if any -- null selects "create new".
  selected: ConfigMap | null;
  // Set only by a 409 from save(); distinct from `error` so the UI can render a dedicated
  // "this changed since you loaded it -- reload?" prompt rather than a plain error toast.
  conflict: { currentVersion: number; currentData: Record<string, string> } | null;
  setTenant(id: string | null): void;
  loadNames(): Promise<void>;
  select(name: string | null): Promise<void>;
  save(name: string, data: Record<string, string>): Promise<void>;
  remove(name: string): Promise<void>;
  dismissConflict(): void;
}

export const useConfigMapsStore = create<State>((set, get) => ({
  tenantId: null,
  names: [],
  loading: false,
  error: null,
  selected: null,
  conflict: null,
  setTenant(id) {
    set({ tenantId: id, names: [], error: null, selected: null, conflict: null });
  },
  async loadNames() {
    const { tenantId } = get();
    if (!tenantId) return;
    set({ loading: true, error: null });
    try {
      const names = await configMapsRepo.fetchNames(tenantId);
      set({ names, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async select(name) {
    const { tenantId } = get();
    set({ conflict: null });
    if (!tenantId || name === null) {
      set({ selected: null });
      return;
    }
    try {
      const configMap = await configMapsRepo.fetchOne(tenantId, name);
      set({ selected: configMap });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async save(name, data) {
    const { tenantId, selected, names } = get();
    if (!tenantId) return;
    // Unconditional overwrite only for a brand-new name never loaded into the edit panel;
    // otherwise the version this panel loaded guards against a save clobbering a concurrent
    // writer's own change.
    const expectedVersion = selected && selected.name === name ? selected.version : 0;
    try {
      const saved = await configMapsRepo.upsert(tenantId, name, data, expectedVersion);
      set({
        selected: saved,
        names: names.includes(name) ? names : [...names, name],
        conflict: null,
      });
    } catch (e) {
      if (e instanceof ConfigMapConflict) {
        set({ conflict: { currentVersion: e.currentVersion, currentData: e.currentData } });
        return;
      }
      set({ error: (e as Error).message });
    }
  },
  async remove(name) {
    const { tenantId, names, selected } = get();
    if (!tenantId) return;
    await configMapsRepo.remove(tenantId, name);
    set({
      names: names.filter((n) => n !== name),
      selected: selected?.name === name ? null : selected,
    });
  },
  dismissConflict() {
    set({ conflict: null });
  },
}));
