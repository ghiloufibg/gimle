import { create } from "zustand";
import type { SealingPublicKey } from "@/types";
import { sealRepo } from "@/repositories";

interface State {
  activeKey: SealingPublicKey | null;
  loading: boolean;
  error: string | null;
  load(): Promise<void>;
  rotate(): Promise<number>;
  retire(keyId: number): Promise<number>;
}

export const useSealStore = create<State>((set, get) => ({
  activeKey: null,
  loading: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const activeKey = await sealRepo.fetchPublicKey();
      set({ activeKey, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async rotate() {
    let activeSealingKeyId: number;
    try {
      ({ activeSealingKeyId } = await sealRepo.rotateKey());
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
    // The rotation response carries only the new id, never the key material, so whatever public
    // key is on screen is stale the instant rotation succeeds -- re-read it rather than patching
    // the id and leaving the base64 beside it belonging to the previous key.
    await get().load();
    return activeSealingKeyId;
  },
  async retire(keyId) {
    try {
      const { retiredKeyId } = await sealRepo.retireKey(keyId);
      return retiredKeyId;
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
  },
}));
