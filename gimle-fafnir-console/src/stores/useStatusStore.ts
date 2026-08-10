import { create } from "zustand";

import { messageOf } from "@/lib/errors";
import { statusRepo } from "@/repositories";
import type { VaultStatus } from "@/types";

interface StatusState {
  status: VaultStatus | null;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
  setActiveKeyId: (activeKeyId: number) => void;
}

export const useStatusStore = create<StatusState>((set, get) => ({
  status: null,
  loading: false,
  error: null,

  async load() {
    set({ loading: true, error: null });
    try {
      const status = await statusRepo.fetchStatus();
      set({ status, loading: false });
    } catch (error) {
      set({ loading: false, error: messageOf(error) });
    }
  },

  setActiveKeyId(activeKeyId) {
    const status = get().status;
    if (status) set({ status: { ...status, activeKeyId } });
  },
}));
