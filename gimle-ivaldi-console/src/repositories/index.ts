import type {
  BlueprintsRepository,
  ClusterConnection,
  ClusterStatus,
  ClustersRepository,
  HilmirValidatorClient,
  RunnerClient,
} from "./contracts";
import { HttpBlueprintsRepository } from "./httpBlueprints";
import { HttpRunnerClient } from "./httpRunner";
import { HttpValidationRepository } from "./httpValidation";
import { LocalStorageClustersRepository } from "./localStorageClusters";
import { MockRunnerClient } from "./mockRunner";

/**
 * Composition root. Wires the real gimle-ivaldi backend for everything it already serves
 * (blueprints CRUD, tier-2 validate — both same-origin /api routes); a resource the backend
 * doesn't serve yet stays on its client-side stand-in. The Mock and LocalStorage implementations
 * exist only for Vitest coverage and as reference implementations of the same interfaces, never
 * behind a runtime toggle here.
 */
export const blueprintsRepository: BlueprintsRepository = new HttpBlueprintsRepository();

/**
 * No /api/clusters backend exists yet (see the design's §05a) — cluster connections are a
 * client-side stand-in ahead of that landing, not a permanent choice.
 */
export const clustersRepository: ClustersRepository = new LocalStorageClustersRepository();

/**
 * No /api/runs backend exists yet either. A cluster with its own runner daemon URL gets an HTTP
 * client against the §05a runner protocol; everything else falls back to the mock so the Designer
 * and Run drawer stay usable while that lands.
 */
export const runnerClient: RunnerClient = new MockRunnerClient();

/**
 * Picks the transport for a selected cluster: a cluster with its own runner
 * daemon URL gets an HTTP client, everything else falls back to the default.
 */
export function runnerClientFor(cluster: ClusterConnection | null): RunnerClient {
  const url = cluster?.runnerUrl?.trim().replace(/\/$/, "");
  return url ? new HttpRunnerClient(url) : runnerClient;
}

/** Probes a cluster's control plane (or its runner daemon) for reachability. */
export async function checkClusterStatus(cluster: ClusterConnection): Promise<ClusterStatus> {
  const base = (cluster.runnerUrl?.trim() || cluster.controlPlaneUrl).replace(/\/$/, "");
  const checkedAt = new Date().toISOString();
  try {
    const res = await fetch(`${base}/v1/health`, {
      headers: { accept: "application/json" },
    });
    if (!res.ok) return { ok: false, version: null, message: `HTTP ${res.status}`, checkedAt };
    const body = (await res.json().catch(() => ({}))) as { version?: string };
    return { ok: true, version: body.version ?? null, message: null, checkedAt };
  } catch (error) {
    return {
      ok: false,
      version: null,
      message: error instanceof Error ? error.message : "unreachable",
      checkedAt,
    };
  }
}

/** Tier-2 validation against gimle-ivaldi's real parsers, same-origin POST /api/validate. */
export const hilmirValidator: HilmirValidatorClient = new HttpValidationRepository();

export { HttpBlueprintsRepository, HttpValidationRepository };

export type { BlueprintsRepository, ClustersRepository, HilmirValidatorClient, RunnerClient };
export type {
  BlueprintSummary,
  ClusterConnection,
  ClusterEnvironment,
  ClusterStatus,
  CreateRunRequest,
  HilmirFinding,
  HilmirReport,
  RunArtifact,
  RunEndpoint,
  RunFile,
  RunLogLine,
  RunSnapshot,
  RunStatus,
  RunStep,
  RunStepStatus,
  RunnerEvent,
  RunnerHealth,
} from "./contracts";
