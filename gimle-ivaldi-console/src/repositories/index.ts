import type {
  BlueprintsRepository,
  ClusterConnection,
  ClusterStatus,
  ClustersRepository,
  HilmirValidatorClient,
  RunnerClient,
} from "./contracts";
import { HttpBlueprintsRepository } from "./httpBlueprints";
import { HttpHilmirValidator } from "./httpHilmir";
import { HttpRunnerClient } from "./httpRunner";
import { HttpValidationRepository } from "./httpValidation";
import { MockHilmirValidator } from "./mockHilmir";
import { MockBlueprintsRepository } from "./localStorageBlueprints";
import { LocalStorageClustersRepository } from "./localStorageClusters";
import { MockRunnerClient } from "./mockRunner";

/**
 * Composition root for blueprint storage.
 * Mock is the default; swap to `new HttpBlueprintsRepository()` to talk to the
 * real gimle-ivaldi backend over same-origin /api paths.
 */
export const blueprintsRepository: BlueprintsRepository = new MockBlueprintsRepository();

export const clustersRepository: ClustersRepository = new LocalStorageClustersRepository();

/**
 * Composition root for the runner transport.
 * Set VITE_RUNNER_API_URL (e.g. http://127.0.0.1:7777) to talk to a real local
 * runner daemon; without it the mock client serves the same contract.
 */
const runnerBaseUrl = (import.meta.env.VITE_RUNNER_API_URL as string | undefined)?.replace(/\/$/, "");

export const runnerClient: RunnerClient = runnerBaseUrl
  ? new HttpRunnerClient(runnerBaseUrl)
  : new MockRunnerClient();

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


/**
 * Composition root for the Hilmir topology validator.
 * Set VITE_HILMIR_API_URL to validate against a real Hilmir; without it the
 * mock validator answers with the same report shape and Hilmir codes.
 */
const hilmirBaseUrl = (import.meta.env.VITE_HILMIR_API_URL as string | undefined)?.replace(/\/$/, "");

/**
 * Tier-2 validation. Mock by default; swap to `new HttpValidationRepository()`
 * for the real same-origin POST /api/validate.
 */
export const hilmirValidator: HilmirValidatorClient = hilmirBaseUrl
  ? new HttpHilmirValidator(hilmirBaseUrl)
  : new MockHilmirValidator();

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
