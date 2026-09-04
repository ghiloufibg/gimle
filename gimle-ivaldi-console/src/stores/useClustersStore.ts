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
  refresh: () => void;
  add: (name: string) => ClusterConnection;
  save: (cluster: ClusterConnection) => void;
  patch: (id: string, patch: Partial<ClusterConnection>) => void;
  remove: (id: string) => void;
  select: (id: string | null) => void;
  selected: () => ClusterConnection | null;
  connect: (id: string) => Promise<void>;
}

export const useClustersStore = create<ClustersState>((set, get) => ({
  clusters: [],
  selectedId: null,
  status: {},
  checking: null,

  refresh: () => {
    const clusters = clustersRepository.list();
    const stored = get().selectedId ?? storedSelection();
    const selectedId = clusters.some((c) => c.id === stored) ? stored : (clusters[0]?.id ?? null);
    set({ clusters, selectedId });
  },

  add: (name) => {
    const cluster = clustersRepository.save(newCluster(name));
    set({ clusters: clustersRepository.list(), selectedId: cluster.id });
    return cluster;
  },

  save: (cluster) => {
    clustersRepository.save(cluster);
    set({ clusters: clustersRepository.list() });
  },

  patch: (id, patch) => {
    const current = clustersRepository.get(id);
    if (!current) return;
    clustersRepository.save({ ...current, ...patch });
    set({ clusters: clustersRepository.list() });
  },

  remove: (id) => {
    clustersRepository.delete(id);
    const clusters = clustersRepository.list();
    const selectedId = get().selectedId === id ? (clusters[0]?.id ?? null) : get().selectedId;
    set({ clusters, selectedId });
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
