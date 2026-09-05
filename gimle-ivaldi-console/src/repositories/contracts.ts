import type { Blueprint } from "@/lib/blueprint";

export type RunStatus =
  "idle" | "validating" | "booting" | "seeding" | "deploying" | "running" | "stopping" | "failed";

export type RunStepStatus = "pending" | "running" | "ok" | "failed" | "skipped";

export interface RunEndpoint {
  label: string;
  url: string;
}

/** One planned unit of work the runner reports on (boot store, deploy bundle, ...). */
export interface RunStep {
  id: string;
  label: string;
  status: RunStepStatus;
  detail?: string;
}

/** A file handed to the runner exactly as it is rendered/exported. */
export interface RunFile {
  path: string;
  content: string;
}

export interface CreateRunRequest {
  blueprintId: string;
  blueprintName: string;
  machine: string;
  files: RunFile[];
  /** Cluster the run is targeted at, when one is selected. */
  clusterId?: string;
  clusterName?: string;
  controlPlaneUrl?: string;
  /** Secret values for this one run only; never persisted anywhere. */
  values?: Record<string, string>;
}

export type ClusterEnvironment = "local" | "dev" | "staging" | "prod";

/** A Gimlé cluster the designer can operate on, identified by its control plane. */
export interface ClusterConnection {
  id: string;
  name: string;
  environment: ClusterEnvironment;
  /** Base URL of the cluster's control plane, e.g. http://127.0.0.1:8080. */
  controlPlaneUrl: string;
  /** Optional local runner daemon that executes blueprints on this cluster. */
  runnerUrl: string | null;
  /** Local path to the mTLS client certificate. Empty for plaintext clusters. */
  clientCertPath: string;
  /** Local path to the mTLS client key. Empty for plaintext clusters. */
  clientKeyPath: string;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface ClusterStatus {
  ok: boolean;
  version: string | null;
  message: string | null;
  checkedAt: string;
}

export interface ClustersRepository {
  list(): Promise<ClusterConnection[]>;
  get(id: string): Promise<ClusterConnection | undefined>;
  save(cluster: ClusterConnection): Promise<ClusterConnection>;
  delete(id: string): Promise<void>;
}

/** One jar-sourced workload pushed to the control plane's artifact service. */
export interface RunArtifact {
  /** Workload the jar belongs to. */
  workload: string;
  tenantId: string | null;
  module: string;
  version: string;
  /** Local jar path as declared in the manifest. */
  path: string;
  /** Gimlé artifact identifier returned by the control plane. */
  artifactId: string | null;
  digest: string | null;
  sizeBytes: number | null;
  status: "pending" | "uploading" | "stored" | "failed";
  server: string;
  error: string | null;
}

export interface RunSnapshot {
  runId: string;
  status: RunStatus;
  steps: RunStep[];
  endpoints: RunEndpoint[];
  artifacts: RunArtifact[];
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
}

export interface RunLogLine {
  seq: number;
  ts: string;
  level: "info" | "warn" | "error";
  source: string;
  text: string;
}

export type RunnerEvent =
  | { type: "snapshot"; snapshot: RunSnapshot }
  | { type: "log"; line: RunLogLine }
  | { type: "artifact"; artifact: RunArtifact }
  | { type: "error"; message: string };

export interface RunnerHealth {
  ok: boolean;
  mode: "mock" | "http";
  version: string | null;
  message: string | null;
}

/**
 * Transport contract for the local Gimlé runner daemon.
 * The mock and the HTTP client implement the exact same calls, so swapping
 * `runnerClient` in the composition root is the only change needed later.
 */
export interface RunnerClient {
  readonly mode: "mock" | "http";
  readonly baseUrl: string | null;
  health(): Promise<RunnerHealth>;
  createRun(request: CreateRunRequest): Promise<RunSnapshot>;
  /**
   * The run the backend holds for {@code blueprintId}, if any -- how a reloaded page finds its way
   * back. Asked per blueprint rather than globally: the backend tracks a run per cluster, so "the
   * current run" is only ever the most recently started one, and reading that on a blueprint which
   * never started it put another cluster's status, endpoints and log on this page.
   */
  currentRun(blueprintId?: string): Promise<RunSnapshot | null>;
  /**
   * Every run the backend is tracking right now, one per cluster. What lets a screen showing many
   * blueprints -- the list, the cluster table -- say which of them own a live cluster, without
   * opening each one's runner in turn.
   */
  listRuns(): Promise<ActiveRun[]>;
  subscribe(runId: string, onEvent: (event: RunnerEvent) => void): () => void;
  stopRun(runId: string): Promise<RunSnapshot>;
}

/** One run the backend is tracking, as the cross-blueprint views need it. */
export interface ActiveRun {
  runId: string;
  clusterId: string | null;
  blueprintId: string | null;
  status: RunStatus;
}

/** A problem reported by Hilmir itself, in Hilmir's own wire shape. */
export interface HilmirFinding {
  code: string;
  severity: "error" | "warning" | "info";
  message: string;
  /** File the finding points at, e.g. topology.yaml or manifests/01-api.yaml. */
  file?: string;
  /** Dotted path inside the document, e.g. store.replicas[0].raftPort. */
  path?: string;
  /** Resource the finding is about, e.g. deployment/api or machine/dev-box. */
  resource?: string;
}

export interface HilmirReport {
  ok: boolean;
  validator: string;
  version: string | null;
  checkedAt: string;
  findings: HilmirFinding[];
  /** Set when the validator itself could not be reached or failed. */
  error: string | null;
}

/**
 * Transport contract for the Hilmir topology validator.
 * The mock and the HTTP client implement the same call, so pointing
 * VITE_HILMIR_API_URL at a real Hilmir is the only change needed later.
 */
export interface HilmirValidatorClient {
  readonly mode: "mock" | "http";
  readonly baseUrl: string | null;
  validate(files: RunFile[]): Promise<HilmirReport>;
}

/** What the list endpoint returns: no counts, only identity + timestamps. */
export interface BlueprintSummary {
  id: string;
  name: string;
  version: string;
  updatedAt: string;
}

export interface BlueprintsRepository {
  readonly mode: "mock" | "http";
  list(): Promise<BlueprintSummary[]>;
  get(id: string): Promise<Blueprint | undefined>;
  /** Create: the id is minted by the server and returned in the summary. */
  create(blueprint: Blueprint): Promise<BlueprintSummary>;
  /** Upsert at a known id. */
  save(blueprint: Blueprint): Promise<BlueprintSummary>;
  delete(id: string): Promise<void>;
}

/** Tier-2 validation transport (same contract as the Hilmir client). */
export type ValidationRepository = HilmirValidatorClient;
