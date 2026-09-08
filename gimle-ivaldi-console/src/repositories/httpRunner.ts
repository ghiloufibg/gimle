import { parse } from "yaml";

import { DEFAULT_PORTS } from "@/lib/ports";
import { applyLogLine, finalizeSteps, initialSteps, markCurrentPhase } from "@/lib/runPhases";

import { fetchWithTimeout } from "./apiClient";

import type {
  ActiveRun,
  CreateRunRequest,
  RunCronJob,
  RunEndpoint,
  RunLogLine,
  RunMachine,
  RunSnapshot,
  RunStep,
  RunnerClient,
  RunnerEvent,
  RunnerHealth,
} from "./contracts";

const POLL_INTERVAL_MS = 1200;

interface RawCronJobFiring {
  name?: string;
  phase?: string;
  firingTime?: string;
}

interface RawCronJob {
  name?: string;
  jobs?: RawCronJobFiring[];
}

/** One process gimle-ivaldi's own launcher started, re-probed for liveness on every poll -- see
 * RunController.refreshedProcesses. `machine` and `role` are what let a client cross-reference this
 * against the topology-derived machine/endpoint lists below; `role` is the launcher's own
 * SCREAMING_SNAKE ProcessRole name (`CONTROL_PLANE`), not the manifest's camelCase field. */
interface RawProcessInfo {
  role?: string;
  machine?: string;
  ready?: boolean;
}

/** The exact shape gimle-ivaldi's RunController.snapshotOf/toJsonMap emits. Steps are still
 * derived from the log (see lib/runPhases.ts) -- `processes` only backs the machines/endpoints
 * readiness cross-reference below, not the step timeline. */
interface RawRunSnapshot {
  id?: string | null;
  clusterId?: string | null;
  blueprintId?: string | null;
  status?: string;
  rebooted?: boolean;
  processes?: RawProcessInfo[];
  cronJobs?: RawCronJob[];
  revision?: number;
  error?: string | null;
  startedAt?: string;
  updatedAt?: string;
}

function cronJobsOf(raw: RawCronJob[] | undefined): RunCronJob[] {
  return (raw ?? []).map((cronJob) => ({
    name: cronJob.name ?? "",
    jobs: (cronJob.jobs ?? []).map((job) => ({
      name: job.name ?? "",
      phase: job.phase ?? "",
      firingTime: job.firingTime ?? "",
    })),
  }));
}

interface TopologyRole {
  machine?: string;
  port?: number;
}

interface Topology {
  transport?: string;
  machines?: { name?: string; host?: string }[];
  store?: { replicas?: TopologyRole[] };
  controlPlane?: { replicas?: TopologyRole[] };
  fafnir?: { replicas?: TopologyRole[] };
  muninn?: { replicas?: TopologyRole[] };
  andvari?: { replicas?: TopologyRole[] };
  agents?: { machine?: string }[];
}

const ROLE_LABEL: Record<string, string> = {
  store: "store",
  controlPlane: "control plane",
  fafnir: "fafnir",
  muninn: "muninn",
  andvari: "andvari",
  agent: "agent",
};

/** Maps a process's own SCREAMING_SNAKE ProcessRole name (the wire form of `processes[].role`)
 * onto the camelCase kind keys the topology's own manifest fields (and ROLE_LABEL above) use. */
const ROLE_KIND_BY_WIRE: Record<string, string> = {
  STORE: "store",
  CONTROL_PLANE: "controlPlane",
  FAFNIR: "fafnir",
  MUNINN: "muninn",
  ANDVARI: "andvari",
  AGENT: "agent",
};

/** True unless `processes` actually reports a not-ready process for this kind/machine -- a
 * deploy-only run leaves `processes` empty, which must read as "nothing fresher to report," not
 * as every role suddenly dead. */
function readinessOf(
  processes: RawProcessInfo[] | undefined,
  matches: (p: RawProcessInfo) => boolean,
): boolean {
  const own = (processes ?? []).filter(matches);
  return own.length === 0 || own.every((p) => p.ready !== false);
}

function safeParseTopology(content: string | undefined): Topology {
  if (!content) return {};
  try {
    return (parse(content) as Topology) ?? {};
  } catch {
    return {};
  }
}

/** Endpoints are static once a topology is known -- derived here rather than reported by the
 * backend, which tracks process readiness (folded in via `processes` below), not link labels. */
function endpointsFromTopologyText(
  content: string | undefined,
  processes?: RawProcessInfo[],
): RunEndpoint[] {
  const topology = safeParseTopology(content);
  const host = topology.machines?.[0]?.host ?? "127.0.0.1";
  // An mTLS cluster's listeners speak TLS only, so an http:// link to one is dead rather than
  // merely unencrypted.
  const scheme = topology.transport === "mtls" ? "https" : "http";
  const readyFor = (kind: string) =>
    readinessOf(processes, (p) => ROLE_KIND_BY_WIRE[p.role ?? ""] === kind);
  const endpoints: RunEndpoint[] = [];
  const cpPort = topology.controlPlane?.replicas?.[0]?.port ?? DEFAULT_PORTS.controlPlane;
  if (topology.controlPlane?.replicas?.length) {
    const ready = readyFor("controlPlane");
    endpoints.push({ label: "Console", url: `${scheme}://${host}:${cpPort}/console`, ready });
    // The control plane's resources sit at the server root -- /deployments, /nodes, /tenants --
    // with no /api prefix, so /healthz is the one path that is both stable and meaningful.
    endpoints.push({
      label: "Control plane health",
      url: `${scheme}://${host}:${cpPort}/healthz`,
      ready,
    });
  }
  const fafnirPort = topology.fafnir?.replicas?.[0]?.port ?? DEFAULT_PORTS.fafnir;
  if (topology.fafnir?.replicas?.length)
    endpoints.push({
      label: "Fafnir vault",
      url: `${scheme}://${host}:${fafnirPort}/console`,
      ready: readyFor("fafnir"),
    });
  const muninnPort = topology.muninn?.replicas?.[0]?.port ?? DEFAULT_PORTS.muninn;
  if (topology.muninn?.replicas?.length)
    endpoints.push({
      label: "Muninn",
      url: `${scheme}://${host}:${muninnPort}/status`,
      ready: readyFor("muninn"),
    });
  const andvariPort = topology.andvari?.replicas?.[0]?.port ?? DEFAULT_PORTS.andvari;
  if (topology.andvari?.replicas?.length)
    endpoints.push({
      label: "Andvari registry",
      url: `${scheme}://${host}:${andvariPort}/console`,
      ready: readyFor("andvari"),
    });
  return endpoints;
}

/** Groups the topology's own machines with the roles placed on each -- a run's process groups, as
 * the wire-level RunSnapshot itself doesn't report placement. */
function machinesFromTopologyText(
  content: string | undefined,
  processes?: RawProcessInfo[],
): RunMachine[] {
  const topology = safeParseTopology(content);
  const roleEntries: { kind: string; machine?: string }[] = [
    ...(topology.store?.replicas ?? []).map((r) => ({ kind: "store", machine: r.machine })),
    ...(topology.controlPlane?.replicas ?? []).map((r) => ({
      kind: "controlPlane",
      machine: r.machine,
    })),
    ...(topology.fafnir?.replicas ?? []).map((r) => ({ kind: "fafnir", machine: r.machine })),
    ...(topology.muninn?.replicas ?? []).map((r) => ({ kind: "muninn", machine: r.machine })),
    ...(topology.andvari?.replicas ?? []).map((r) => ({ kind: "andvari", machine: r.machine })),
    ...(topology.agents ?? []).map((a) => ({ kind: "agent", machine: a.machine })),
  ];
  return (topology.machines ?? [])
    .filter((m): m is { name: string; host: string } => Boolean(m.name && m.host))
    .map((m) => ({
      name: m.name,
      host: m.host,
      roles: roleEntries
        .filter((r) => r.machine === m.name)
        .map((r) => ROLE_LABEL[r.kind] ?? r.kind),
      ready: readinessOf(processes, (p) => p.machine === m.name),
    }));
}

function mapStatus(raw: string | undefined): RunSnapshot["status"] {
  const known: RunSnapshot["status"][] = [
    "idle",
    "validating",
    "booting",
    "seeding",
    "deploying",
    "running",
    "stopping",
    "failed",
  ];
  return known.includes(raw as RunSnapshot["status"]) ? (raw as RunSnapshot["status"]) : "failed";
}

function currentPhaseFor(
  status: RunSnapshot["status"],
): "validate" | "boot" | "seed" | "deploy" | "active" | null {
  switch (status) {
    case "validating":
      return "validate";
    case "booting":
      return "boot";
    case "seeding":
      return "seed";
    case "deploying":
      return "deploy";
    case "running":
      return "active";
    default:
      return null;
  }
}

/**
 * Talks to the real gimle-ivaldi backend's same-origin /api/runs* surface. No SSE: gimle-ivaldi
 * reports a coarse status plus a plain-text log, so this polls GET /api/runs/for-blueprint/{id} and
 * GET /api/runs/{id}/log?cursor=N on an interval and replays them as the same snapshot/log/error
 * events a real event stream would emit -- useRunStore doesn't know the difference.
 *
 * `baseUrl` is `null` for the common case (this same Ivaldi, same-origin relative paths); a
 * cluster with its own runnerUrl gets an absolute base instead -- see runnerClientFor.
 */
export class HttpRunnerClient implements RunnerClient {
  readonly mode = "http" as const;

  constructor(readonly baseUrl: string | null = null) {}

  private url(path: string): string {
    return this.baseUrl ? `${this.baseUrl}${path}` : path;
  }

  async health(): Promise<RunnerHealth> {
    try {
      const res = await fetchWithTimeout(this.url("/api/health"), {
        headers: { accept: "application/json" },
      });
      if (!res.ok)
        return { ok: false, mode: this.mode, version: null, message: `HTTP ${res.status}` };
      return { ok: true, mode: this.mode, version: null, message: null };
    } catch (error) {
      return {
        ok: false,
        mode: this.mode,
        version: null,
        message: error instanceof Error ? error.message : "ivaldi unreachable",
      };
    }
  }

  async createRun(request: CreateRunRequest): Promise<RunSnapshot> {
    const res = await fetchWithTimeout(this.url("/api/runs"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        clusterId: request.clusterId,
        blueprintId: request.blueprintId,
        files: request.files,
        // Secret values ride this one request and are stored nowhere: the backend applies them
        // to the bundle's ${values.*} placeholders and they never reach a rendered file.
        ...(request.values && Object.keys(request.values).length ? { values: request.values } : {}),
      }),
    });
    if (!res.ok) throw new Error(`ivaldi ${res.status}: ${await res.text()}`);
    const raw = (await res.json()) as RawRunSnapshot;
    const topologyText = request.files.find((f) => f.path === "topology.yaml")?.content;
    const endpoints = endpointsFromTopologyText(topologyText, raw.processes);
    const machines = machinesFromTopologyText(topologyText, raw.processes);
    return this.toSnapshot(raw, initialSteps(), endpoints, machines);
  }

  /**
   * The run gimle-ivaldi is currently holding for one blueprint, or null when it holds none.
   * `GET /api/runs/for-blueprint/{id}` stays unambiguous even once a cluster hosts several
   * blueprints' own deployments, so a reloaded page can pick a live (or failed,
   * still-holding-a-process-tree) run back up instead of showing "nothing ever ran". Steps are
   * rebuilt from the log by the subscription that follows, so an attached run's timeline fills in
   * from its first poll -- see lib/runPhases.ts. `blueprintId` is optional only for a direct,
   * low-level caller with no blueprint of its own, which falls back to the ambiguous
   * `/api/runs/current`.
   */
  async currentRun(blueprintId?: string): Promise<RunSnapshot | null> {
    try {
      const path = blueprintId
        ? `/api/runs/for-blueprint/${encodeURIComponent(blueprintId)}`
        : "/api/runs/current";
      const res = await fetchWithTimeout(this.url(path), {
        headers: { accept: "application/json" },
      });
      if (!res.ok) return null;
      const raw = (await res.json()) as RawRunSnapshot;
      if (!raw.id || mapStatus(raw.status) === "idle") return null;
      const topologyText = raw.clusterId ? await this.fetchTopologyText(raw.clusterId) : undefined;
      const endpoints = endpointsFromTopologyText(topologyText, raw.processes);
      const machines = machinesFromTopologyText(topologyText, raw.processes);
      return this.toSnapshot(raw, initialSteps(), endpoints, machines);
    } catch {
      return null;
    }
  }

  async listRuns(): Promise<ActiveRun[]> {
    try {
      const res = await fetchWithTimeout(this.url("/api/runs"), {
        headers: { accept: "application/json" },
      });
      if (!res.ok) return [];
      const raw = (await res.json()) as RawRunSnapshot[];
      return raw
        .filter((r) => r.id && mapStatus(r.status) !== "idle")
        .map((r) => ({
          runId: r.id as string,
          clusterId: r.clusterId ?? null,
          blueprintId: r.blueprintId ?? null,
          status: mapStatus(r.status),
        }));
    } catch {
      // A backend that cannot be reached holds no runs as far as any caller here is concerned.
      return [];
    }
  }

  subscribe(runId: string, blueprintId: string, onEvent: (event: RunnerEvent) => void): () => void {
    let steps = initialSteps();
    let endpoints: RunEndpoint[] = [];
    let machines: RunMachine[] = [];
    // Cached separately from endpoints/machines themselves: the topology text only needs
    // re-fetching until it settles (see below), but readiness must be recomputed from every
    // poll's own fresh `processes`, even once the topology fetch has stopped.
    let topologyText: string | undefined;
    let endpointsSettled = false;
    let cursor = 0;
    let seq = 0;
    let stopped = false;
    let inFlight = false;

    const poll = async () => {
      if (stopped || inFlight) return;
      inFlight = true;
      try {
        // Scoped to this blueprint, not the ambiguous /api/runs/current: a cluster can now host
        // more than one deployment, so "the most recently started run across the whole backend"
        // could belong to a different blueprint entirely, on the same cluster or a different one.
        const [snapshotRes, logRes] = await Promise.all([
          fetchWithTimeout(this.url(`/api/runs/for-blueprint/${encodeURIComponent(blueprintId)}`), {
            headers: { accept: "application/json" },
          }),
          fetchWithTimeout(
            this.url(`/api/runs/${encodeURIComponent(runId)}/log?cursor=${cursor}`),
            {
              headers: { accept: "application/json" },
            },
          ),
        ]);
        if (logRes.ok) {
          const page = (await logRes.json()) as { lines?: string[]; nextCursor?: number };
          for (const line of page.lines ?? []) {
            steps = applyLogLine(steps, line);
            onEvent({
              type: "log",
              line: logLineOf(seq++, line),
            });
          }
          cursor = page.nextCursor ?? cursor;
        }
        if (!snapshotRes.ok) {
          onEvent({ type: "error", message: `HTTP ${snapshotRes.status}` });
          return;
        }
        const raw = (await snapshotRes.json()) as RawRunSnapshot;
        if (raw.id && raw.id !== runId) return; // a later run superseded this one
        const status = mapStatus(raw.status);
        // Re-read until the run settles, rather than caching the first answer: that first poll
        // lands during the boot, when the cluster still holds its *previous* applied topology, so
        // caching then pinned the old scheme and host -- every link dead after a
        // plaintext-to-mTLS switch.
        if (raw.clusterId && (topologyText === undefined || !endpointsSettled)) {
          topologyText = await this.fetchTopologyText(raw.clusterId);
          endpointsSettled = status === "running" || status === "failed";
        }
        endpoints = endpointsFromTopologyText(topologyText, raw.processes);
        machines = machinesFromTopologyText(topologyText, raw.processes);
        const phase = currentPhaseFor(status);
        if (phase) steps = markCurrentPhase(steps, phase);
        if (status === "running" || status === "failed") steps = finalizeSteps(steps, status);
        onEvent({ type: "snapshot", snapshot: this.toSnapshot(raw, steps, endpoints, machines) });
        // Polling continues past `running` and `failed`: both still own a live process tree, and a
        // stop from either is asynchronous, so this poll is the only thing that can carry the run
        // to idle. Stopping here left the screen reading STOPPING forever while the backend had
        // already torn the cluster down. `idle` is the one terminal state.
        if (status === "idle") {
          stopped = true;
          window.clearInterval(timer);
        }
      } catch (error) {
        onEvent({ type: "error", message: error instanceof Error ? error.message : "poll failed" });
      } finally {
        inFlight = false;
      }
    };

    void poll();
    const timer = window.setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }

  async stopRun(runId: string, blueprintId: string): Promise<RunSnapshot> {
    // Scoped to this blueprint for the same reason #subscribe is: /api/runs/current would stop
    // whichever deployment across the whole backend happened to start most recently, not
    // necessarily this one.
    const res = await fetchWithTimeout(
      this.url(`/api/runs/for-blueprint/${encodeURIComponent(blueprintId)}`),
      { method: "DELETE" },
    );
    if (!res.ok) throw new Error(`ivaldi ${res.status}: ${await res.text()}`);
    const raw = (await res.json()) as RawRunSnapshot;
    return this.toSnapshot({ ...raw, id: raw.id ?? runId }, [], [], []);
  }

  /** The cluster's own last-applied topology, fetched once per subscription and cached by the
   * caller -- there is no per-run topology endpoint, only a per-cluster one. */
  private async fetchTopologyText(clusterId: string): Promise<string | undefined> {
    try {
      const res = await fetchWithTimeout(
        this.url(`/api/clusters/${encodeURIComponent(clusterId)}/topology`),
        {
          headers: { accept: "application/json" },
        },
      );
      if (!res.ok) return undefined;
      const body = (await res.json()) as { topology?: string | null };
      return body.topology ?? undefined;
    } catch {
      return undefined;
    }
  }

  private toSnapshot(
    raw: RawRunSnapshot,
    steps: RunStep[],
    endpoints: RunEndpoint[],
    machines: RunMachine[],
  ): RunSnapshot {
    const status = mapStatus(raw.status);
    const settled = status === "idle" || status === "running" || status === "failed";
    return {
      runId: raw.id ?? "",
      status,
      steps,
      endpoints: status === "running" ? endpoints : [],
      // Unlike endpoints, shown throughout the run rather than only once running: which machine
      // hosts which process is exactly what a boot in progress needs to show for a multi-machine
      // topology, not only its finished state.
      machines,
      artifacts: [],
      cronJobs: cronJobsOf(raw.cronJobs),
      startedAt: raw.startedAt ?? new Date().toISOString(),
      finishedAt: settled ? (raw.updatedAt ?? null) : null,
      error: raw.error ?? null,
      // Absent (rather than 0) until the bundle's own first deploy actually lands -- 0 would read
      // as a real revision rather than "none yet".
      revision: typeof raw.revision === "number" ? raw.revision : null,
    };
  }
}

function logLineOf(seq: number, text: string): RunLogLine {
  return {
    seq,
    ts: new Date().toISOString(),
    level: text.includes("FAILED:") ? "error" : "info",
    source: "ivaldi",
    text,
  };
}
