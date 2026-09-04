import { create } from "zustand";

import type { Blueprint } from "@/lib/blueprint";
import { firstMachineName, renderFiles } from "@/lib/render";
import {
  runnerClient,
  runnerClientFor,
  type ClusterConnection,
  type CreateRunRequest,
  type RunArtifact,
  type RunEndpoint,
  type RunLogLine,
  type RunSnapshot,
  type RunStatus,
  type RunStep,
  type RunnerHealth,
} from "@/repositories";

import { useValidationStore } from "./useValidationStore";

interface RunState {
  runId: string | null;
  status: RunStatus;
  steps: RunStep[];
  endpoints: RunEndpoint[];
  artifacts: RunArtifact[];
  log: RunLogLine[];
  request: CreateRunRequest | null;
  health: RunnerHealth | null;
  reason: string | null;
  busy: boolean;
  transport: { mode: "mock" | "http"; baseUrl: string | null };
  cluster: ClusterConnection | null;
  setCluster: (cluster: ClusterConnection | null) => void;
  checkHealth: () => Promise<void>;
  start: (blueprint: Blueprint, cluster?: ClusterConnection | null) => Promise<void>;
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
    artifacts: snapshot.artifacts ?? [],
    reason: snapshot.error,
  };
}

export const useRunStore = create<RunState>((set, get) => ({
  runId: null,
  status: "idle",
  steps: [],
  endpoints: [],
  artifacts: [],
  log: [],
  request: null,
  health: null,
  reason: null,
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

  start: async (blueprint, clusterArg) => {
    if (get().busy) return;
    const errors = useValidationStore.getState().errorCount();
    if (errors > 0) {
      set({ reason: `${errors} error${errors === 1 ? "" : "s"} must be fixed before running.` });
      return;
    }
    unsubscribe?.();
    unsubscribe = null;

    const cluster = clusterArg !== undefined ? clusterArg : get().cluster;
    if (!cluster) {
      set({ reason: "Choose a cluster to run this blueprint on." });
      return;
    }
    const client = runnerClientFor(cluster);

    const request: CreateRunRequest = {
      blueprintId: blueprint.id,
      blueprintName: blueprint.name,
      machine: firstMachineName(blueprint),
      files: renderFiles(blueprint).map((file) => ({ path: file.path, content: file.content })),
      clusterId: cluster.id,
      clusterName: cluster.name,
      controlPlaneUrl: cluster.controlPlaneUrl,
    };
    set({
      busy: true,
      reason: null,
      log: [],
      endpoints: [],
      steps: [],
      artifacts: [],
      request,
      cluster,
      transport: { mode: client.mode, baseUrl: client.baseUrl },
      status: "validating",
    });

    try {
      const snapshot = await client.createRun(request);
      set({ ...applySnapshot(snapshot), busy: false });
      unsubscribe = client.subscribe(snapshot.runId, (event) => {
        if (event.type === "log") set((state) => ({ log: [...state.log, event.line] }));
        else if (event.type === "artifact")
          set((state) => ({
            artifacts: state.artifacts.some((a) => a.workload === event.artifact.workload)
              ? state.artifacts.map((a) =>
                  a.workload === event.artifact.workload ? event.artifact : a,
                )
              : [...state.artifacts, event.artifact],
          }));
        else if (event.type === "snapshot") set(applySnapshot(event.snapshot));
        else set({ status: "failed", reason: event.message });
      });
    } catch (error) {
      set({
        busy: false,
        status: "failed",
        reason: error instanceof Error ? error.message : "runner unreachable",
      });
    }
  },

  stop: async () => {
    const { runId, status } = get();
    if (!runId || status === "idle") return;
    set({ status: "stopping", busy: true });
    try {
      const snapshot = await runnerClientFor(get().cluster).stopRun(runId);
      unsubscribe?.();
      unsubscribe = null;
      set({ ...applySnapshot(snapshot), busy: false });
    } catch (error) {
      set({
        busy: false,
        status: "failed",
        reason: error instanceof Error ? error.message : "stop failed",
      });
    }
  },

  clearLog: () => set({ log: [] }),
}));
