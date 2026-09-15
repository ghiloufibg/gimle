import { create } from "zustand";
import type { ControlPlaneStatus } from "@/types";
import { controlPlaneHealthRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

interface State {
  status: ControlPlaneStatus | null;
  loading: boolean;
  error: string | null;
  load(): Promise<void>;
  poll(): Promise<void>;
}

export const useControlPlaneHealthStore = create<State>((set, get) => ({
  status: null,
  loading: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const status = await controlPlaneHealthRepo.fetch();
      set({ status, loading: false });
    } catch (e) {
      set({ loading: false, error: storeErrorMessage(e) });
    }
  },
  async poll() {
    if (get().loading) return;
    try {
      const status = await controlPlaneHealthRepo.fetch();
      set({ status, error: null });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
}));
