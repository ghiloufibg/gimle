import type {
  BlueprintsRepository,
  ClusterConnection,
  ClusterStatus,
  ClustersRepository,
  HilmirValidatorClient,
  RunnerClient,
} from "./contracts";
import { HttpBlueprintsRepository } from "./httpBlueprints";
import { HttpClustersRepository } from "./httpClusters";
import { HttpRunnerClient } from "./httpRunner";
import { HttpValidationRepository } from "./httpValidation";

/**
 * Composition root. Wires the real gimle-ivaldi backend for everything it now serves —
 * blueprints CRUD, tier-2 validate, cluster connections, and running a Blueprint — all
 * same-origin /api routes on this Ivaldi instance itself. The Mock and LocalStorage
 * implementations exist only for Vitest coverage and as reference implementations of the same
 * interfaces, never behind a runtime toggle here.
 */
export const blueprintsRepository: BlueprintsRepository = new HttpBlueprintsRepository();

export const clustersRepository: ClustersRepository = new HttpClustersRepository();

/**
 * The default runner: this same Ivaldi instance, same-origin (no base URL, matching every other
 * repository here). A cluster with its own runnerUrl configured gets a client against that
 * instance's /api/runs* instead — see runnerClientFor.
 */
export const runnerClient: RunnerClient = new HttpRunnerClient();

/**
 * Picks the transport for a selected cluster: a cluster with its own runner
 * daemon URL gets an HTTP client against that URL's own /api/runs*, everything else
 * (runnerUrl unset, the common case) falls back to this same Ivaldi instance.
 */
export function runnerClientFor(cluster: ClusterConnection | null): RunnerClient {
  const url = cluster?.runnerUrl?.trim().replace(/\/$/, "");
  return url ? new HttpRunnerClient(url) : runnerClient;
}

/** Probes a cluster's runner (this Ivaldi, or a configured runnerUrl) for reachability. */
/**
 * Whether this cluster's own control plane is answering. Asked of Ivaldi, which probes it
 * server-side: a control plane sends no CORS headers, so the browser cannot reach an arbitrary one
 * itself. Probing Ivaldi's own /api/health instead -- which is what this used to do -- reported
 * every cluster reachable, including one with nothing listening on its port, which is the single
 * thing this check exists to catch.
 */
export async function checkClusterStatus(cluster: ClusterConnection): Promise<ClusterStatus> {
  const base = cluster.runnerUrl?.trim().replace(/\/$/, "") ?? "";
  const checkedAt = new Date().toISOString();
  try {
    const res = await fetch(`${base}/api/clusters/${encodeURIComponent(cluster.id)}/health`, {
      headers: { accept: "application/json" },
    });
    if (!res.ok) return { ok: false, version: null, message: `HTTP ${res.status}`, checkedAt };
    const body = (await res.json()) as { ok?: boolean; message?: string | null };
    return {
      ok: body.ok === true,
      version: null,
      message: body.message ?? null,
      checkedAt,
    };
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
  ActiveRun,
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
