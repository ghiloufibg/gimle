import { create } from "zustand";
import type { RoleBinding } from "@/types";
import { roleBindingsRepo } from "@/repositories";

interface State {
  items: RoleBinding[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  save(id: string, body: { subject: string; roleName: string }): Promise<void>;
  remove(id: string): Promise<void>;
}

export const useRoleBindingsStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await roleBindingsRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  async save(id, body) {
    set({ loading: true, error: null });
    try {
      await roleBindingsRepo.save(id, body);
      const items = await roleBindingsRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(id) {
    set({ loading: true, error: null });
    try {
      await roleBindingsRepo.remove(id);
      set({ items: get().items.filter((b) => b.id !== id), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
