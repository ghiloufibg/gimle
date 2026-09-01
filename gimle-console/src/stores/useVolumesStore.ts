import { create } from "zustand";
import type { Volume } from "@/types";
import { volumesRepo } from "@/repositories";
import { storeErrorMessage } from "@/lib/api-error";

interface State {
  volumes: Volume[];
  /** Nodes whose agent did not answer this listing. Empty means the table is complete. */
  unreachableNodes: string[];
  loading: boolean;
  error: string | null;
  load(): Promise<void>;
  poll(): Promise<void>;
  destroy(volume: Volume): Promise<void>;
}

export const useVolumesStore = create<State>((set, get) => ({
  volumes: [],
  unreachableNodes: [],
  loading: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const listing = await volumesRepo.fetchAll();
      set({
        volumes: listing.volumes,
        unreachableNodes: listing.unreachableNodes ?? [],
        loading: false,
      });
    } catch (e) {
      // Keep whatever was last listed rather than blanking the table: an empty table alongside an
      // error reads as "nothing to reclaim", the opposite of what a failed refresh means.
      set({ loading: false, error: (e as Error).message });
    }
  },
  /** The screen's auto-refresh read: no `loading` flag, so the Refresh button never flickers
   * disabled under the pointer and the table it guards is not replaced mid-glance. */
  async poll() {
    if (get().loading) return;
    try {
      const listing = await volumesRepo.fetchAll();
      set({
        volumes: listing.volumes,
        unreachableNodes: listing.unreachableNodes ?? [],
        error: null,
      });
    } catch (e) {
      set({ error: storeErrorMessage(e) });
    }
  },
  async destroy(volume) {
    try {
      // The row's own tenant is always passed through rather than left to the server: a destroy
      // that omits it addresses the default tenant, which for a tenanted volume is a different
      // volume entirely -- the wrong thing to get wrong on an irreversible operation.
      await volumesRepo.destroy(
        volume.nodeId,
        volume.statefulSet,
        volume.instanceIndex,
        volume.tenantId,
      );
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
    // Re-read the whole listing rather than splicing the row out locally: the destroy went through
    // the owning node's own agent, and the unreachable-node set may have changed with it.
    await get().load();
  },
}));
