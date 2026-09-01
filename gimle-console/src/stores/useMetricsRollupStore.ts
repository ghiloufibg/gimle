import { create } from "zustand";
import type { DeploymentMetricsRollup } from "@/types";
import { metricsRepo } from "@/repositories";

interface State {
  rows: DeploymentMetricsRollup[];
  loaded: boolean;
  loading: boolean;
  error: string | null;
  load(): Promise<void>;
}

/**
 * The server's own per-deployment request/error-rate rollup. Deliberately not derived from
 * useOverviewStore's deployment list: the rollup's instanceCount is the number of instances that
 * actually contributed a metrics reading, which a client-side average over placed instances
 * cannot distinguish from "placed but never reported".
 */
export const useMetricsRollupStore = create<State>((set, get) => ({
  rows: [],
  loaded: false,
  loading: false,
  error: null,
  async load() {
    if (get().loading) return;
    set({ loading: true, error: null });
    try {
      const rows = await metricsRepo.fetchRollup();
      set({ rows, loaded: true, loading: false });
    } catch (e) {
      // The previously loaded rows stay put: a failed refresh should not blank a panel that was
      // showing real data a moment ago, only annotate it with why it is now stale.
      set({ loading: false, error: (e as Error).message });
    }
  },
}));
