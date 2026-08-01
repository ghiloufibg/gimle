import { create } from "zustand";
import type { Deployment, Node } from "@/types";
import { deploymentsRepo, nodesRepo, tenantsRepo } from "@/repositories";

interface State {
  loaded: boolean;
  loading: boolean;
  error: string | null;
  nodesTotal: number;
  nodesStale: number;
  deploymentsTotal: number;
  tenantsTotal: number;
  unplacedInstances: number;
  quotaViolating: number;
  recentDeployments: Deployment[];
  recentNodes: Node[];
  // Full-cluster arrays for screens that plot/group every deployment or node (Topology, Metrics),
  // as opposed to recentDeployments/recentNodes above, which are fetchSummary()'s capped 8-item
  // preview meant for the Overview dashboard's compact panels. Populated separately via loadAll()
  // since fetchSummary() intentionally never returns more than 8 rows.
  allDeployments: Deployment[];
  allNodes: Node[];
  allLoaded: boolean;
  allLoading: boolean;
  load(): Promise<void>;
  loadAll(): Promise<void>;
}

export const useOverviewStore = create<State>((set, get) => ({
  loaded: false,
  loading: false,
  error: null,
  nodesTotal: 0,
  nodesStale: 0,
  deploymentsTotal: 0,
  tenantsTotal: 0,
  unplacedInstances: 0,
  quotaViolating: 0,
  recentDeployments: [],
  recentNodes: [],
  allDeployments: [],
  allNodes: [],
  allLoaded: false,
  allLoading: false,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const [d, n, t] = await Promise.all([
        deploymentsRepo.fetchSummary(),
        nodesRepo.fetchSummary(),
        tenantsRepo.fetchSummary(),
      ]);
      set({
        loading: false,
        loaded: true,
        deploymentsTotal: d.total,
        unplacedInstances: d.unplacedInstances,
        quotaViolating: d.quotaViolating,
        recentDeployments: d.recent,
        nodesTotal: n.total,
        nodesStale: n.stale,
        recentNodes: n.recent,
        tenantsTotal: t.total,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async loadAll() {
    if (get().allLoading) return;
    set({ allLoading: true, error: null });
    try {
      const [d, n] = await Promise.all([
        deploymentsRepo.fetchPage({ cursor: null, pageSize: 10_000 }),
        nodesRepo.fetchPage({ cursor: null, pageSize: 10_000 }),
      ]);
      set({
        allLoading: false,
        allLoaded: true,
        allDeployments: d.items,
        allNodes: n.items,
      });
    } catch (e) {
      set({ allLoading: false, error: (e as Error).message });
    }
  },
}));
