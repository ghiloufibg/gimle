import { parse } from "yaml";

import type {
  CreateRunRequest,
  RunArtifact,
  RunEndpoint,
  RunLogLine,
  RunSnapshot,
  RunStep,
  RunnerClient,
  RunnerEvent,
  RunnerHealth,
} from "./contracts";

interface ScriptEntry {
  delay: number;
  stepId?: string;
  stepStatus?: RunStep["status"];
  detail?: string;
  status?: RunSnapshot["status"];
  source: string;
  level?: RunLogLine["level"];
  text: string;
  endpoints?: RunEndpoint[];
  /** Artifact record to merge into the snapshot (matched by workload). */
  artifact?: RunArtifact;
}

interface Session {
  snapshot: RunSnapshot;
  script: ScriptEntry[];
  timers: ReturnType<typeof setTimeout>[];
  seq: number;
}

interface TopologyRole {
  machine?: string;
  port?: number;
  raftPort?: number;
  clientPort?: number;
  nodeId?: string;
  gossipPort?: number;
}

interface Topology {
  name?: string;
  machines?: { name?: string; host?: string }[];
  store?: { replicas?: TopologyRole[] };
  controlPlane?: { replicas?: TopologyRole[] };
  fafnir?: { replicas?: TopologyRole[] };
  muninn?: { replicas?: TopologyRole[] };
  andvari?: { replicas?: TopologyRole[] };
  agents?: TopologyRole[];
}

const DEFAULT_PORT: Record<string, number> = {
  store: 9091,
  controlPlane: 8080,
  fafnir: 9092,
  muninn: 9093,
  andvari: 9094,
  agent: 9090,
};

function safeParse<T>(content: string | undefined): T | null {
  if (!content) return null;
  try {
    return parse(content) as T;
  } catch {
    return null;
  }
}

/** Stable content hash — stands in for the sha the control plane computes. */
function hashHex(input: string, length: number): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < input.length; i++) {
    h1 = Math.imul(h1 ^ input.charCodeAt(i), 16777619) >>> 0;
    h2 = Math.imul(h2 + input.charCodeAt(i) + i, 2654435761) >>> 0;
  }
  let out = "";
  let a = h1;
  let b = h2;
  while (out.length < length) {
    out += a.toString(16).padStart(8, "0") + b.toString(16).padStart(8, "0");
    a = Math.imul(a ^ 0x9e3779b9, 16777619) >>> 0;
    b = Math.imul(b ^ 0x85ebca6b, 2246822519) >>> 0;
  }
  return out.slice(0, length);
}

interface ManifestDoc {
  kind?: string;
  name?: string;
  tenantId?: string;
  artifactPath?: string;
  module?: { name?: string; version?: string };
  jobTemplate?: { module?: { name?: string; version?: string } };
}

/**
 * Builds the same event stream a real runner daemon would emit, from the very
 * same payload (topology.yaml + manifests) that is POSTed to it.
 */
function buildScript(request: CreateRunRequest): {
  steps: RunStep[];
  script: ScriptEntry[];
  artifacts: RunArtifact[];
} {
  const byPath = new Map(request.files.map((f) => [f.path, f.content]));
  const topology = safeParse<Topology>(byPath.get("topology.yaml")) ?? {};
  const host = topology.machines?.[0]?.host ?? "127.0.0.1";
  const manifests = request.files.filter((f) => f.path.startsWith("manifests/"));

  const steps: RunStep[] = [];
  const script: ScriptEntry[] = [];
  const addStep = (id: string, label: string) => steps.push({ id, label, status: "pending" });

  addStep("validate", "Validate topology");
  script.push({
    delay: 0,
    stepId: "validate",
    stepStatus: "running",
    status: "validating",
    source: "hilmir",
    text: `validate -f topology.yaml (${request.files.length} files received)`,
  });
  script.push({
    delay: 500,
    stepId: "validate",
    stepStatus: "ok",
    source: "hilmir",
    text: "topology.yaml ok",
  });

  const roles: { kind: string; entry: TopologyRole }[] = [
    ...(topology.store?.replicas ?? []).map((entry) => ({ kind: "store", entry })),
    ...(topology.muninn?.replicas ?? []).map((entry) => ({ kind: "muninn", entry })),
    ...(topology.andvari?.replicas ?? []).map((entry) => ({ kind: "andvari", entry })),
    ...(topology.fafnir?.replicas ?? []).map((entry) => ({ kind: "fafnir", entry })),
    ...(topology.controlPlane?.replicas ?? []).map((entry) => ({ kind: "controlPlane", entry })),
  ];

  roles.forEach(({ kind, entry }, index) => {
    const id = `boot:${kind}:${index}`;
    const machine = entry.machine ?? request.machine;
    const port = entry.port ?? entry.clientPort ?? DEFAULT_PORT[kind] ?? 0;
    addStep(id, `Boot ${kind} on ${machine}`);
    script.push({
      delay: 420,
      stepId: id,
      stepStatus: "running",
      status: "booting",
      source: kind,
      text: `starting on ${machine}:${port}`,
    });
    script.push({
      delay: 380,
      stepId: id,
      stepStatus: "ok",
      detail: `${machine}:${port}`,
      source: kind,
      text: "READY",
    });
  });

  const agents = topology.agents ?? [];
  if (agents.length) {
    addStep("agents", `Join ${agents.length} agent${agents.length === 1 ? "" : "s"}`);
    script.push({
      delay: 300,
      stepId: "agents",
      stepStatus: "running",
      source: "agent",
      text: "joining gossip ring",
    });
    for (const agent of agents) {
      script.push({
        delay: 260,
        source: "agent",
        text: `${agent.nodeId ?? "node"}: joined on ${agent.machine ?? request.machine} (gossip ${agent.gossipPort ?? DEFAULT_PORT.agent})`,
      });
    }
    script.push({
      delay: 200,
      stepId: "agents",
      stepStatus: "ok",
      source: "agent",
      text: "ring stable",
    });
  }

  const cpPort = topology.controlPlane?.replicas?.[0]?.port ?? DEFAULT_PORT.controlPlane;
  const server = `${host}:${cpPort}`;
  const jarDocs = manifests
    .map((f) => ({ file: f, doc: safeParse<ManifestDoc>(f.content) }))
    .filter((m): m is { file: (typeof manifests)[number]; doc: ManifestDoc } =>
      Boolean(m.doc?.artifactPath),
    );

  const artifacts: RunArtifact[] = jarDocs.map(({ file, doc }) => {
    const mod = doc.module ?? doc.jobTemplate?.module ?? {};
    return {
      workload: doc.name ?? file.path,
      tenantId: doc.tenantId ?? null,
      module: mod.name ?? doc.name ?? "module",
      version: mod.version ?? "0.0.0",
      path: doc.artifactPath ?? file.path,
      artifactId: null,
      digest: null,
      sizeBytes: null,
      status: "pending",
      server,
      error: null,
    };
  });

  if (artifacts.length) {
    addStep(
      "artifacts",
      `Push ${artifacts.length} local artifact${artifacts.length === 1 ? "" : "s"}`,
    );
    script.push({
      delay: 300,
      stepId: "artifacts",
      stepStatus: "running",
      status: "seeding",
      source: "gimle",
      text: `artifact push --server ${server} (${artifacts.length} jar${artifacts.length === 1 ? "" : "s"})`,
    });
    for (const artifact of artifacts) {
      const digest = `sha256:${hashHex(`${artifact.module}@${artifact.version}:${artifact.path}`, 64)}`;
      const artifactId = `gma_${hashHex(`${artifact.workload}:${digest}`, 20)}`;
      const sizeBytes = 4_000_000 + (parseInt(digest.slice(7, 13), 16) % 38_000_000);
      script.push({
        delay: 260,
        source: "gimle",
        text: `uploading ${artifact.path} (${artifact.module}@${artifact.version}) -> ${server}`,
        artifact: { ...artifact, status: "uploading", digest, sizeBytes },
      });
      script.push({
        delay: 340,
        source: "controlPlane",
        text: `artifact stored id=${artifactId} digest=${digest.slice(0, 19)}… size=${(sizeBytes / 1_048_576).toFixed(1)}MiB workload=${artifact.workload}`,
        artifact: { ...artifact, status: "stored", digest, sizeBytes, artifactId },
      });
    }
    script.push({
      delay: 200,
      stepId: "artifacts",
      stepStatus: "ok",
      detail: `${artifacts.length} stored`,
      source: "gimle",
      text: "artifacts available",
    });
  }

  addStep("deploy", `Apply bundle (${manifests.length} manifests)`);
  script.push({
    delay: 320,
    stepId: "deploy",
    stepStatus: "running",
    status: "deploying",
    source: "hilmir",
    text: `deploy -f bundle.yaml --values values.yaml --server ${host}:${cpPort} --wait`,
  });
  for (const manifest of manifests) {
    const kind = /kind:\s*(\w+)/.exec(manifest.content)?.[1] ?? "Resource";
    const name = /name:\s*(.+)/.exec(manifest.content)?.[1]?.trim() ?? manifest.path;
    script.push({ delay: 240, source: "controlPlane", text: `${kind}/${name} reconciled` });
  }
  script.push({
    delay: 300,
    stepId: "deploy",
    stepStatus: "ok",
    source: "hilmir",
    text: "bundle applied",
  });

  addStep("active", "Wait for ACTIVE");
  script.push({
    delay: 200,
    stepId: "active",
    stepStatus: "running",
    source: "controlPlane",
    text: "waiting for ACTIVE",
  });

  const endpoints: RunEndpoint[] = [];
  if (topology.controlPlane?.replicas?.length) {
    endpoints.push({ label: "Console", url: `http://${host}:${cpPort}/console` });
    endpoints.push({ label: "Control plane API", url: `http://${host}:${cpPort}/api` });
  }
  const muninnPort = topology.muninn?.replicas?.[0]?.port;
  if (topology.muninn?.replicas?.length)
    endpoints.push({ label: "Muninn", url: `http://${host}:${muninnPort ?? DEFAULT_PORT.muninn}` });
  const andvariPort = topology.andvari?.replicas?.[0]?.port;
  if (topology.andvari?.replicas?.length)
    endpoints.push({
      label: "Andvari registry",
      url: `http://${host}:${andvariPort ?? DEFAULT_PORT.andvari}`,
    });

  script.push({
    delay: 600,
    stepId: "active",
    stepStatus: "ok",
    status: "running",
    source: "cluster",
    text: "cluster ACTIVE",
    endpoints,
  });

  return { steps, script, artifacts };
}

export class MockRunnerClient implements RunnerClient {
  readonly mode = "mock" as const;
  readonly baseUrl = null;

  private sessions = new Map<string, Session>();

  async health(): Promise<RunnerHealth> {
    return {
      ok: true,
      mode: this.mode,
      version: "mock-0.1",
      message: "Simulated runner — no processes are started on this machine.",
    };
  }

  async createRun(request: CreateRunRequest): Promise<RunSnapshot> {
    const { steps, script, artifacts } = buildScript(request);
    const runId = `run-${Date.now().toString(36)}`;
    const snapshot: RunSnapshot = {
      runId,
      status: "validating",
      steps,
      endpoints: [],
      artifacts,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      error: null,
    };
    this.sessions.set(runId, { snapshot, script, timers: [], seq: 0 });
    return snapshot;
  }

  async currentRun(): Promise<RunSnapshot | null> {
    for (const session of this.sessions.values()) {
      if (session.snapshot.status !== "idle") return session.snapshot;
    }
    return null;
  }

  subscribe(runId: string, onEvent: (event: RunnerEvent) => void): () => void {
    const session = this.sessions.get(runId);
    if (!session) {
      onEvent({ type: "error", message: `unknown run ${runId}` });
      return () => {};
    }
    onEvent({ type: "snapshot", snapshot: session.snapshot });

    let elapsed = 0;
    for (const entry of session.script) {
      elapsed += entry.delay;
      session.timers.push(
        setTimeout(() => {
          const next: RunSnapshot = {
            ...session.snapshot,
            status: entry.status ?? session.snapshot.status,
            endpoints: entry.endpoints ?? session.snapshot.endpoints,
            artifacts: entry.artifact
              ? session.snapshot.artifacts.map((a) =>
                  a.workload === entry.artifact!.workload ? entry.artifact! : a,
                )
              : session.snapshot.artifacts,
            steps: session.snapshot.steps.map((step) =>
              step.id === entry.stepId
                ? {
                    ...step,
                    status: entry.stepStatus ?? step.status,
                    detail: entry.detail ?? step.detail,
                  }
                : step,
            ),
            finishedAt:
              entry.status === "running" ? new Date().toISOString() : session.snapshot.finishedAt,
          };
          session.snapshot = next;
          onEvent({
            type: "log",
            line: {
              seq: session.seq++,
              ts: new Date().toISOString(),
              level: entry.level ?? "info",
              source: entry.source,
              text: entry.text,
            },
          });
          if (entry.artifact) onEvent({ type: "artifact", artifact: entry.artifact });
          onEvent({ type: "snapshot", snapshot: next });
        }, elapsed),
      );
    }

    return () => this.clear(runId);
  }

  async stopRun(runId: string): Promise<RunSnapshot> {
    const session = this.sessions.get(runId);
    this.clear(runId);
    const snapshot: RunSnapshot = session
      ? {
          ...session.snapshot,
          status: "idle",
          endpoints: [],
          finishedAt: new Date().toISOString(),
          steps: session.snapshot.steps.map((step) =>
            step.status === "running" || step.status === "pending"
              ? { ...step, status: "skipped" }
              : step,
          ),
        }
      : {
          runId,
          status: "idle",
          steps: [],
          endpoints: [],
          artifacts: [],
          startedAt: new Date().toISOString(),
          finishedAt: new Date().toISOString(),
          error: null,
        };
    if (session) session.snapshot = snapshot;
    return snapshot;
  }

  private clear(runId: string): void {
    const session = this.sessions.get(runId);
    if (!session) return;
    for (const timer of session.timers) clearTimeout(timer);
    session.timers = [];
  }
}
