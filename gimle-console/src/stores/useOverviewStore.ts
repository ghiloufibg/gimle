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
  load(): Promise<void>;
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
}));
