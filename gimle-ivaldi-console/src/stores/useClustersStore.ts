import { create } from "zustand";

import { uid } from "@/lib/blueprint";
import {
  checkClusterStatus,
  clustersRepository,
  type ClusterConnection,
  type ClusterEnvironment,
  type ClusterStatus,
} from "@/repositories";

const SELECTED_KEY = "ivaldi.clusters.selected";
/**
 * Which cluster each blueprint targets, kept beside the global default rather than replacing it:
 * one selection shared by every blueprint meant choosing a target on one changed it for all of
 * them, so pressing Run on a second blueprint could submit its topology to the first one's
 * cluster with nothing on screen tying the two together. Local to this browser, not part of the
 * blueprint document -- which cluster a design is aimed at is an operator's choice, not something
 * exported in a zip.
 */
const PER_BLUEPRINT_KEY = "ivaldi.clusters.byBlueprint";

function storedSelection(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(SELECTED_KEY);
}

function storedTargets(): Record<string, string> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(PER_BLUEPRINT_KEY);
    return raw ? (JSON.parse(raw) as Record<string, string>) : {};
  } catch {
    return {};
  }
}

export function newCluster(name: string): ClusterConnection {
  const ts = new Date().toISOString();
  return {
    id: uid("cluster"),
    name,
    environment: "local",
    controlPlaneUrl: "http://127.0.0.1:8080",
    runnerUrl: null,
    clientCertPath: "",
    clientKeyPath: "",
    description: "",
    createdAt: ts,
    updatedAt: ts,
  };
}

interface ClustersState {
  clusters: ClusterConnection[];
  selectedId: string | null;
  status: Record<string, ClusterStatus>;
  checking: string | null;
  error: string | null;
  refresh: () => Promise<void>;
  add: (name: string) => Promise<ClusterConnection>;
  save: (cluster: ClusterConnection) => Promise<void>;
  patch: (id: string, patch: Partial<ClusterConnection>) => Promise<void>;
  remove: (id: string) => Promise<void>;
  select: (id: string | null) => void;
  selected: () => ClusterConnection | null;
  /** The cluster this blueprint targets, or the global default when it has never chosen one. */
  selectedFor: (blueprintId: string) => ClusterConnection | null;
  selectFor: (blueprintId: string, clusterId: string) => void;
  connect: (id: string) => Promise<void>;
}

export const useClustersStore = create<ClustersState>((set, get) => ({
  clusters: [],
  selectedId: null,
  status: {},
  checking: null,
  error: null,

  refresh: async () => {
    try {
      const clusters = await clustersRepository.list();
      const stored = get().selectedId ?? storedSelection();
      const selectedId = clusters.some((c) => c.id === stored) ? stored : (clusters[0]?.id ?? null);
      set({ clusters, selectedId, error: null });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  add: async (name) => {
    const cluster = await clustersRepository.save(newCluster(name));
    set({ clusters: await clustersRepository.list(), selectedId: cluster.id });
    return cluster;
  },

  save: async (cluster) => {
    try {
      await clustersRepository.save(cluster);
      set({ clusters: await clustersRepository.list(), error: null });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  patch: async (id, patch) => {
    try {
      const current = await clustersRepository.get(id);
      if (!current) return;
      await clustersRepository.save({ ...current, ...patch });
      set({ clusters: await clustersRepository.list(), error: null });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  remove: async (id) => {
    try {
      await clustersRepository.delete(id);
      const clusters = await clustersRepository.list();
      const selectedId = get().selectedId === id ? (clusters[0]?.id ?? null) : get().selectedId;
      set({ clusters, selectedId, error: null });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  select: (id) => {
    if (typeof window !== "undefined" && id) window.localStorage.setItem(SELECTED_KEY, id);
    set({ selectedId: id });
  },

  selected: () => get().clusters.find((c) => c.id === get().selectedId) ?? null,

  selectedFor: (blueprintId) => {
    const own = storedTargets()[blueprintId];
    const found = own ? get().clusters.find((c) => c.id === own) : undefined;
    return found ?? get().selected();
  },

  selectFor: (blueprintId, clusterId) => {
    if (typeof window !== "undefined") {
      const targets = { ...storedTargets(), [blueprintId]: clusterId };
      window.localStorage.setItem(PER_BLUEPRINT_KEY, JSON.stringify(targets));
    }
    // The most recent choice also becomes the default a blueprint with none of its own inherits.
    get().select(clusterId);
  },

  connect: async (id) => {
    const cluster = get().clusters.find((c) => c.id === id);
    if (!cluster) return;
    set({ checking: id });
    const status = await checkClusterStatus(cluster);
    set((state) => ({ status: { ...state.status, [id]: status }, checking: null }));
  },
}));

export const ENVIRONMENTS: ClusterEnvironment[] = ["local", "dev", "staging", "prod"];
