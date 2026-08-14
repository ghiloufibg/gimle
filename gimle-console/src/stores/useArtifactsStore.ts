import { create } from "zustand";
import type { ArtifactVersion } from "@/types";
import { artifactsRepo } from "@/repositories";

interface State {
  moduleIds: string[];
  selectedModuleId: string | null;
  versions: ArtifactVersion[];
  loading: boolean;
  error: string | null;
  loadCatalog(): Promise<void>;
  select(moduleId: string | null): Promise<void>;
  remove(moduleId: string, version: string): Promise<void>;
}

export const useArtifactsStore = create<State>((set, get) => ({
  moduleIds: [],
  selectedModuleId: null,
  versions: [],
  loading: false,
  error: null,
  async loadCatalog() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const moduleIds = await artifactsRepo.fetchCatalog();
      set({ moduleIds, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async select(moduleId) {
    if (moduleId === null) {
      set({ selectedModuleId: null, versions: [] });
      return;
    }
    // Clear the outgoing module's rows up front: they belong to a different coordinate, and
    // leaving them on screen while the new ones load reads as if they were the new module's.
    set({ selectedModuleId: moduleId, versions: [], loading: true, error: null });
    try {
      const versions = await artifactsRepo.fetchVersions(moduleId);
      set({ versions, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async remove(moduleId, version) {
    try {
      await artifactsRepo.remove(moduleId, version);
    } catch (e) {
      set({ error: (e as Error).message });
      throw e;
    }
    // Re-read both lists from the registry rather than splicing locally: deleting a module's last
    // version also drops it from the catalog, so the two are never independently up to date.
    const moduleIds = await artifactsRepo.fetchCatalog().catch(() => get().moduleIds);
    set({ moduleIds });
    await get().select(moduleIds.includes(moduleId) ? moduleId : null);
  },
}));
