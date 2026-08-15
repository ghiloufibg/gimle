import { create } from "zustand";
import { ApiError, artifactsRepo } from "@/repositories";
import type { ArtifactPushResult, ArtifactVersion } from "@/types";

interface ArtifactsState {
  catalog: string[];
  catalogLoading: boolean;
  error: string | null;
  versionsByModule: Record<string, ArtifactVersion[]>;
  moduleLoading: boolean;
  moduleError: string | null;
  pushPending: boolean;
  pushError: string | null;
  pushConflict: boolean;
  deletePending: string | null;
  loadCatalog: () => Promise<void>;
  loadModule: (moduleId: string) => Promise<void>;
  loadAll: () => Promise<void>;
  push: (moduleId: string, version: string, file: File) => Promise<ArtifactPushResult | null>;
  remove: (moduleId: string, version: string) => Promise<boolean>;
  download: (moduleId: string, version: string) => Promise<void>;
  resetPushState: () => void;
}

export const useArtifactsStore = create<ArtifactsState>((set, get) => ({
  catalog: [],
  catalogLoading: false,
  error: null,
  versionsByModule: {},
  moduleLoading: false,
  moduleError: null,
  pushPending: false,
  pushError: null,
  pushConflict: false,
  deletePending: null,

  loadCatalog: async () => {
    set({ catalogLoading: true });
    try {
      const catalog = await artifactsRepo.catalog();
      set({ catalog, catalogLoading: false, error: null });
    } catch (error) {
      set({
        catalogLoading: false,
        error: error instanceof Error ? error.message : "failed to load catalog",
      });
    }
  },

  loadModule: async (moduleId) => {
    set({ moduleLoading: true, moduleError: null });
    try {
      const entry = await artifactsRepo.module(moduleId);
      set((state) => ({
        moduleLoading: false,
        versionsByModule: { ...state.versionsByModule, [moduleId]: entry.versions },
      }));
    } catch (error) {
      set({
        moduleLoading: false,
        moduleError: error instanceof Error ? error.message : "failed to load module",
      });
    }
  },

  loadAll: async () => {
    await get().loadCatalog();
    const { catalog } = get();
    await Promise.all(catalog.map((moduleId) => get().loadModule(moduleId)));
  },

  push: async (moduleId, version, file) => {
    set({ pushPending: true, pushError: null, pushConflict: false });
    try {
      const result = await artifactsRepo.push(moduleId, version, file);
      set({ pushPending: false });
      await get().loadCatalog();
      await get().loadModule(moduleId);
      return result;
    } catch (error) {
      const conflict = error instanceof ApiError && error.status === 409;
      set({
        pushPending: false,
        pushConflict: conflict,
        pushError: conflict
          ? "this exact moduleId:version already exists — an artifact version is immutable once pushed; bump the version and push again"
          : error instanceof Error
            ? error.message
            : "upload failed",
      });
      return null;
    }
  },

  remove: async (moduleId, version) => {
    set({ deletePending: `${moduleId}:${version}` });
    try {
      await artifactsRepo.remove(moduleId, version);
      set({ deletePending: null });
      await get().loadCatalog();
      await get().loadModule(moduleId);
      return true;
    } catch (error) {
      set({
        deletePending: null,
        moduleError: error instanceof Error ? error.message : "delete failed",
      });
      return false;
    }
  },

  download: async (moduleId, version) => {
    const blob = await artifactsRepo.download(moduleId, version);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `${moduleId}-${version}.jar`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  },

  resetPushState: () => set({ pushError: null, pushConflict: false, pushPending: false }),
}));

export function selectRecentPushes(
  state: Pick<ArtifactsState, "versionsByModule">,
  limit = 6,
): ArtifactVersion[] {
  return Object.values(state.versionsByModule)
    .flat()
    .sort((a, b) => b.pushedAtEpochMilli - a.pushedAtEpochMilli)
    .slice(0, limit);
}
