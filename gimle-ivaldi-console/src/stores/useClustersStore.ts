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

function storedSelection(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(SELECTED_KEY);
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

  connect: async (id) => {
    const cluster = get().clusters.find((c) => c.id === id);
    if (!cluster) return;
    set({ checking: id });
    const status = await checkClusterStatus(cluster);
    set((state) => ({ status: { ...state.status, [id]: status }, checking: null }));
  },
}));

export const ENVIRONMENTS: ClusterEnvironment[] = ["local", "dev", "staging", "prod"];
