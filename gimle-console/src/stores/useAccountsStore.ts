import { create } from "zustand";
import type { Account } from "@/types";
import { accountsRepo } from "@/repositories";

interface State {
  items: Account[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(): Promise<void>;
  refresh(): Promise<void>;
  savePassword(username: string, password: string, groups?: string[]): Promise<void>;
  remove(username: string): Promise<void>;
}

export const useAccountsStore = create<State>((set, get) => ({
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const items = await accountsRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ loaded: false });
    await get().load();
  },
  async savePassword(username, password, groups) {
    set({ loading: true, error: null });
    try {
      await accountsRepo.savePassword(username, password, groups);
      const items = await accountsRepo.fetchAll();
      set({ items, loading: false, loaded: true });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
  async remove(username) {
    set({ loading: true, error: null });
    try {
      await accountsRepo.remove(username);
      set({ items: get().items.filter((a) => a.username !== username), loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
      throw e;
    }
  },
}));
