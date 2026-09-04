import { parse } from "yaml";

import { DEFAULT_PORTS } from "@/lib/ports";
import { applyLogLine, finalizeSteps, initialSteps, markCurrentPhase } from "@/lib/runPhases";

import type {
  CreateRunRequest,
  RunEndpoint,
  RunLogLine,
  RunSnapshot,
  RunStep,
  RunnerClient,
  RunnerEvent,
  RunnerHealth,
} from "./contracts";

const POLL_INTERVAL_MS = 1200;

/** The exact shape gimle-ivaldi's RunController.snapshotOf/toJsonMap emits. `processes` exists
 * on the wire but nothing here reads it yet -- steps are derived from the log instead (see
 * lib/runPhases.ts), the finer-grained per-process readiness has no UI consumer today. */
interface RawRunSnapshot {
  id?: string | null;
  clusterId?: string | null;
  status?: string;
  rebooted?: boolean;
  error?: string | null;
  startedAt?: string;
  updatedAt?: string;
}

interface TopologyRole {
  port?: number;
}

interface Topology {
  transport?: string;
  machines?: { host?: string }[];
  controlPlane?: { replicas?: TopologyRole[] };
  fafnir?: { replicas?: TopologyRole[] };
  muninn?: { replicas?: TopologyRole[] };
  andvari?: { replicas?: TopologyRole[] };
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
 * backend, which tracks process readiness, not link labels. */
function endpointsFromTopologyText(content: string | undefined): RunEndpoint[] {
  const topology = safeParseTopology(content);
  const host = topology.machines?.[0]?.host ?? "127.0.0.1";
  // An mTLS cluster's listeners speak TLS only, so an http:// link to one is dead rather than
  // merely unencrypted.
  const scheme = topology.transport === "mtls" ? "https" : "http";
  const endpoints: RunEndpoint[] = [];
  const cpPort = topology.controlPlane?.replicas?.[0]?.port ?? DEFAULT_PORTS.controlPlane;
  if (topology.controlPlane?.replicas?.length) {
    endpoints.push({ label: "Console", url: `${scheme}://${host}:${cpPort}/console` });
    // The control plane's resources sit at the server root -- /deployments, /nodes, /tenants --
    // with no /api prefix, so /healthz is the one path that is both stable and meaningful.
    endpoints.push({ label: "Control plane health", url: `${scheme}://${host}:${cpPort}/healthz` });
  }
  const fafnirPort = topology.fafnir?.replicas?.[0]?.port ?? DEFAULT_PORTS.fafnir;
  if (topology.fafnir?.replicas?.length)
    endpoints.push({ label: "Fafnir vault", url: `${scheme}://${host}:${fafnirPort}/console` });
  const muninnPort = topology.muninn?.replicas?.[0]?.port ?? DEFAULT_PORTS.muninn;
  if (topology.muninn?.replicas?.length)
    endpoints.push({ label: "Muninn", url: `${scheme}://${host}:${muninnPort}` });
  const andvariPort = topology.andvari?.replicas?.[0]?.port ?? DEFAULT_PORTS.andvari;
  if (topology.andvari?.replicas?.length)
    endpoints.push({ label: "Andvari registry", url: `${scheme}://${host}:${andvariPort}/console` });
  return endpoints;
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
 * reports a coarse status plus a plain-text log, so this polls GET /api/runs/current and
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
      const res = await fetch(this.url("/api/health"), { headers: { accept: "application/json" } });
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
    const res = await fetch(this.url("/api/runs"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        clusterId: request.clusterId,
        blueprintId: request.blueprintId,
        files: request.files,
      }),
    });
    if (!res.ok) throw new Error(`ivaldi ${res.status}: ${await res.text()}`);
    const raw = (await res.json()) as RawRunSnapshot;
    const endpoints = endpointsFromTopologyText(
      request.files.find((f) => f.path === "topology.yaml")?.content,
    );
    return this.toSnapshot(raw, initialSteps(), endpoints);
  }

  /**
   * The run gimle-ivaldi is currently holding, or null when it holds none. Written against the
   * real backend rather than Lovable's own `/v1/runs/current` sketch: there is one run at a time
   * and `GET /api/runs/current` is its whole state, so a reloaded page can pick a live (or failed,
   * still-holding-a-process-tree) run back up instead of showing "nothing ever ran". Steps are
   * rebuilt from the log by the subscription that follows, so an attached run's timeline fills in
   * from its first poll -- see lib/runPhases.ts.
   */
  async currentRun(): Promise<RunSnapshot | null> {
    try {
      const res = await fetch(this.url("/api/runs/current"), {
        headers: { accept: "application/json" },
      });
      if (!res.ok) return null;
      const raw = (await res.json()) as RawRunSnapshot;
      if (!raw.id || mapStatus(raw.status) === "idle") return null;
      const endpoints = raw.clusterId ? await this.fetchEndpoints(raw.clusterId) : [];
      return this.toSnapshot(raw, initialSteps(), endpoints);
    } catch {
      return null;
    }
  }

  subscribe(runId: string, onEvent: (event: RunnerEvent) => void): () => void {
    let steps = initialSteps();
    let endpoints: RunEndpoint[] = [];
    let cursor = 0;
    let seq = 0;
    let stopped = false;

    const poll = async () => {
      if (stopped) return;
      try {
        const [snapshotRes, logRes] = await Promise.all([
          fetch(this.url("/api/runs/current"), { headers: { accept: "application/json" } }),
          fetch(this.url(`/api/runs/${encodeURIComponent(runId)}/log?cursor=${cursor}`), {
            headers: { accept: "application/json" },
          }),
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
        if (endpoints.length === 0 && raw.clusterId) {
          endpoints = await this.fetchEndpoints(raw.clusterId);
        }
        const status = mapStatus(raw.status);
        const phase = currentPhaseFor(status);
        if (phase) steps = markCurrentPhase(steps, phase);
        if (status === "running" || status === "failed") {
          steps = finalizeSteps(steps, status);
          stopped = true;
        }
        onEvent({ type: "snapshot", snapshot: this.toSnapshot(raw, steps, endpoints) });
        if (stopped) window.clearInterval(timer);
      } catch (error) {
        onEvent({ type: "error", message: error instanceof Error ? error.message : "poll failed" });
      }
    };

    void poll();
    const timer = window.setInterval(poll, POLL_INTERVAL_MS);
    return () => {
      stopped = true;
      window.clearInterval(timer);
    };
  }

  async stopRun(runId: string): Promise<RunSnapshot> {
    const res = await fetch(this.url("/api/runs/current"), { method: "DELETE" });
    if (!res.ok) throw new Error(`ivaldi ${res.status}: ${await res.text()}`);
    const raw = (await res.json()) as RawRunSnapshot;
    return this.toSnapshot({ ...raw, id: raw.id ?? runId }, [], []);
  }

  /** The cluster's own last-applied topology, fetched once per subscription and cached by the
   * caller -- there is no per-run topology endpoint, only a per-cluster one. */
  private async fetchEndpoints(clusterId: string): Promise<RunEndpoint[]> {
    try {
      const res = await fetch(this.url(`/api/clusters/${encodeURIComponent(clusterId)}/topology`), {
        headers: { accept: "application/json" },
      });
      if (!res.ok) return [];
      const body = (await res.json()) as { topology?: string | null };
      return body.topology ? endpointsFromTopologyText(body.topology) : [];
    } catch {
      return [];
    }
  }

  private toSnapshot(raw: RawRunSnapshot, steps: RunStep[], endpoints: RunEndpoint[]): RunSnapshot {
    const status = mapStatus(raw.status);
    const settled = status === "idle" || status === "running" || status === "failed";
    return {
      runId: raw.id ?? "",
      status,
      steps,
      endpoints: status === "running" ? endpoints : [],
      artifacts: [],
      startedAt: raw.startedAt ?? new Date().toISOString(),
      finishedAt: settled ? (raw.updatedAt ?? null) : null,
      error: raw.error ?? null,
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
