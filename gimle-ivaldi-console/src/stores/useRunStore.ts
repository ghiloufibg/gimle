import { create } from "zustand";

import type { Blueprint } from "@/lib/blueprint";
import { firstMachineName, renderFiles } from "@/lib/render";
import {
  runnerClient,
  runnerClientFor,
  type ClusterConnection,
  type CreateRunRequest,
  type RunEndpoint,
  type RunLogLine,
  type RunMachine,
  type RunSnapshot,
  type RunStatus,
  type RunStep,
  type RunnerClient,
  type RunnerHealth,
} from "@/repositories";

import { useClustersStore } from "./useClustersStore";
import { useValidationStore } from "./useValidationStore";

/** Statuses during which a new run must not be started. */
export const IN_FLIGHT: RunStatus[] = ["validating", "booting", "seeding", "deploying"];

interface StartOptions {
  cluster?: ClusterConnection | null;
  /** Secret values for this request only. Never stored. */
  values?: Record<string, string>;
}

interface RunState {
  runId: string | null;
  /** Which blueprint owns {@code runId} -- what addresses this run unambiguously against the
   * backend's own per-blueprint endpoints, now that a cluster can host more than one deployment. */
  blueprintId: string | null;
  status: RunStatus;
  steps: RunStep[];
  endpoints: RunEndpoint[];
  machines: RunMachine[];
  log: RunLogLine[];
  request: CreateRunRequest | null;
  health: RunnerHealth | null;
  reason: string | null;
  /** A stop that failed: persists until the next start or stop. */
  stopError: string | null;
  busy: boolean;
  transport: { mode: "mock" | "http"; baseUrl: string | null };
  cluster: ClusterConnection | null;
  setCluster: (cluster: ClusterConnection | null) => void;
  checkHealth: () => Promise<void>;
  /** Re-attach to the run the backend is holding for this blueprint, after a page reload. */
  attach: (blueprintId?: string) => Promise<void>;
  start: (blueprint: Blueprint, options?: StartOptions) => Promise<void>;
  stop: () => Promise<void>;
  clearLog: () => void;
}

let unsubscribe: (() => void) | null = null;

function applySnapshot(snapshot: RunSnapshot) {
  return {
    runId: snapshot.runId,
    status: snapshot.status,
    steps: snapshot.steps,
    endpoints: snapshot.endpoints,
    machines: snapshot.machines,
    reason: snapshot.error,
  };
}

/**
 * Resolves the cluster to run against without depending on any component having mounted: the
 * picker is only one way of choosing it, and the designer's own Run button navigates to the
 * runner rather than waiting for that picker to appear.
 */
async function resolveCluster(
  explicit: ClusterConnection | null | undefined,
): Promise<ClusterConnection | null> {
  if (explicit !== undefined) return explicit;
  const current = useRunStore.getState().cluster;
  if (current) return current;
  const clusters = useClustersStore.getState();
  // Awaited, not fired and forgotten: on a cold load the list is still empty, and reading the
  // selection before it arrives is what made the first Run click do nothing at all.
  if (clusters.clusters.length === 0) await clusters.refresh();
  return useClustersStore.getState().selected();
}

export const useRunStore = create<RunState>((set, get) => {
  /** Subscribes to a run and keeps the store in step with it. */
  const listen = (client: RunnerClient, runId: string, blueprintId: string) => {
    unsubscribe?.();
    unsubscribe = client.subscribe(runId, blueprintId, (event) => {
      if (event.type === "log") set((state) => ({ log: [...state.log, event.line] }));
      else if (event.type === "snapshot") {
        set(applySnapshot(event.snapshot));
        // A stop finishes asynchronously, so the subscription is what carries the run to idle --
        // dropping it at the moment Stop was pressed left the screen reading STOPPING forever.
        if (event.snapshot.status === "idle") {
          unsubscribe?.();
          unsubscribe = null;
        }
      } else if (event.type === "error") set({ status: "failed", reason: event.message });
    });
  };

  return {
    runId: null,
    blueprintId: null,
    status: "idle",
    steps: [],
    endpoints: [],
    machines: [],
    log: [],
    request: null,
    health: null,
    reason: null,
    stopError: null,
    busy: false,
    transport: { mode: runnerClient.mode, baseUrl: runnerClient.baseUrl },
    cluster: null,

    setCluster: (cluster) => {
      const client = runnerClientFor(cluster);
      set({ cluster, transport: { mode: client.mode, baseUrl: client.baseUrl }, health: null });
      void client.health().then((health) => set({ health }));
    },

    checkHealth: async () => {
      set({ health: await runnerClientFor(get().cluster).health() });
    },

    /**
     * The backend keeps a run's whole state, including after it fails with a process tree still
     * up. Nothing in this store survives a page reload, though, so without this the screen would
     * come back reading "idle / 0 lines" with Stop disabled -- the one control that could tear
     * that tree down. Asked for one blueprint: the backend tracks a run per cluster, so without
     * naming the blueprint this would re-attach to whichever run happened to start last.
     */
    attach: async (blueprintId) => {
      if (get().runId || get().busy) return;
      const client = runnerClientFor(await resolveCluster(undefined));
      let snapshot: RunSnapshot | null = null;
      try {
        snapshot = await client.currentRun(blueprintId);
      } catch {
        return;
      }
      if (!snapshot || snapshot.status === "idle") return;
      set({
        ...applySnapshot(snapshot),
        blueprintId: blueprintId ?? null,
        transport: { mode: client.mode, baseUrl: client.baseUrl },
      });
      // Without a blueprint id there is no per-blueprint endpoint to poll (see contracts.ts) -- a
      // defensive path for a direct, low-level caller only; the runner page always passes one.
      if (blueprintId) listen(client, snapshot.runId, blueprintId);
    },

    start: async (blueprint, options) => {
      if (get().busy) return;
      const errors = useValidationStore.getState().errorCount();
      if (errors > 0) {
        set({ reason: `${errors} error${errors === 1 ? "" : "s"} must be fixed before running.` });
        return;
      }

      const cluster = await resolveCluster(options?.cluster);
      if (!cluster) {
        set({ reason: "Choose a cluster to run this blueprint on." });
        return;
      }
      const client = runnerClientFor(cluster);

      let files: CreateRunRequest["files"];
      try {
        files = renderFiles(blueprint).map((file) => ({ path: file.path, content: file.content }));
      } catch (error) {
        set({
          status: "failed",
          reason: error instanceof Error ? error.message : "could not render this blueprint",
        });
        return;
      }

      const request: CreateRunRequest = {
        blueprintId: blueprint.id,
        blueprintName: blueprint.name,
        machine: firstMachineName(blueprint),
        files,
        clusterId: cluster.id,
        clusterName: cluster.name,
        controlPlaneUrl: cluster.controlPlaneUrl,
        ...(options?.values && Object.keys(options.values).length
          ? { values: options.values }
          : {}),
      };
      unsubscribe?.();
      unsubscribe = null;
      set({
        busy: true,
        reason: null,
        stopError: null,
        log: [],
        endpoints: [],
        machines: [],
        steps: [],
        request,
        blueprintId: blueprint.id,
        cluster,
        transport: { mode: client.mode, baseUrl: client.baseUrl },
        status: "validating",
      });

      try {
        const snapshot = await client.createRun(request);
        set({ ...applySnapshot(snapshot), busy: false });
        listen(client, snapshot.runId, blueprint.id);
      } catch (error) {
        set({
          busy: false,
          status: "failed",
          reason: error instanceof Error ? error.message : "runner unreachable",
        });
      }
    },

    stop: async () => {
      const { runId, blueprintId, status } = get();
      if (!runId || !blueprintId || status === "idle") return;
      set({ status: "stopping", busy: true, stopError: null });
      try {
        // Keep the subscription alive: the backend tears down asynchronously and the poll is what
        // carries the run to idle.
        const snapshot = await runnerClientFor(get().cluster).stopRun(runId, blueprintId);
        set({ ...applySnapshot(snapshot), busy: false });
        if (snapshot.status === "idle") {
          unsubscribe?.();
          unsubscribe = null;
        }
      } catch (error) {
        set({
          busy: false,
          stopError: error instanceof Error ? error.message : "stop failed",
        });
      }
    },

    clearLog: () => set({ log: [] }),
  };
});
