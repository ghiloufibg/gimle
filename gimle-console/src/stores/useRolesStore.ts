import { create } from "zustand";
import type { Permission, Role } from "@/types";
import { rolesRepo } from "@/repositories";

interface State {
  items: Role[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  save(name: string, permissions: Permission[]): Promise<void>;
  remove(name: string): Promise<void>;
}

export const useRolesStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await rolesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  async save(name, permissions) {
    set({ loading: true, error: null });
    try {
      await rolesRepo.save(name, permissions);
      const items = await rolesRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(name) {
    set({ loading: true, error: null });
    try {
      await rolesRepo.remove(name);
      set({ items: get().items.filter((r) => r.name !== name), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
