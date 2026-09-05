import { create } from "zustand";

import { runnerClientFor, type ActiveRun } from "@/repositories";

import { useClustersStore } from "./useClustersStore";

/**
 * Which blueprints and clusters currently own a live cluster, as every screen that shows more
 * than one of them needs it.
 *
 * <p>Kept apart from useRunStore, which follows the single run this tab started and holds its
 * log, steps and endpoints. That store cannot answer "is this other blueprint running", and
 * without an answer the list, the designer and the cluster table all showed a live cluster as
 * indistinguishable from one that had never been booted.
 */
interface ActiveRunsState {
  runs: ActiveRun[];
  refresh: () => Promise<void>;
  byBlueprint: (blueprintId: string) => ActiveRun | undefined;
  byCluster: (clusterId: string) => ActiveRun | undefined;
}

export const useActiveRunsStore = create<ActiveRunsState>((set, get) => ({
  runs: [],

  refresh: async () => {
    const client = runnerClientFor(useClustersStore.getState().selected());
    set({ runs: await client.listRuns() });
  },

  byBlueprint: (blueprintId) => get().runs.find((r) => r.blueprintId === blueprintId),
  byCluster: (clusterId) => get().runs.find((r) => r.clusterId === clusterId),
}));

/** How often a screen showing live state re-asks. Slow: nothing here drives an action. */
export const ACTIVE_RUNS_POLL_MS = 5000;
