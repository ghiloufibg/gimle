import { create } from "zustand";
import { statusRepo } from "@/repositories";
import type { RegistryStatus } from "@/types";

interface StatusState {
  status: RegistryStatus | null;
  reachable: boolean;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

export const useStatusStore = create<StatusState>((set) => ({
  status: null,
  reachable: false,
  loading: false,
  error: null,
  refresh: async () => {
    set({ loading: true });
    try {
      const status = await statusRepo.status();
      set({ status, reachable: true, loading: false, error: null });
    } catch (error) {
      set({
        reachable: false,
        loading: false,
        error: error instanceof Error ? error.message : "status unreachable",
      });
    }
  },
}));
