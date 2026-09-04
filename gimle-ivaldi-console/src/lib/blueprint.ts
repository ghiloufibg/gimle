export type Severity = "error" | "warning" | "info";

export interface Problem {
  code: string;
  severity: Severity;
  message: string;
  nodeId?: string;
  edgeId?: string;
  file?: string;
}

export type PlatformKind =
  "machine" | "store" | "controlPlane" | "fafnir" | "muninn" | "andvari" | "agent";

export type AppKind =
  | "tenant"
  | "deployment"
  | "statefulSet"
  | "daemonSet"
  | "job"
  | "cronJob"
  | "service"
  | "networkPolicy"
  | "configEntry"
  | "secret"
  | "limitRange";

export type NodeKind = PlatformKind | AppKind;

export const PLATFORM_KINDS: PlatformKind[] = [
  "machine",
  "store",
  "controlPlane",
  "fafnir",
  "muninn",
  "andvari",
  "agent",
];

export const APP_KINDS: AppKind[] = [
  "tenant",
  "deployment",
  "statefulSet",
  "daemonSet",
  "job",
  "cronJob",
  "service",
  "networkPolicy",
  "configEntry",
  "secret",
  "limitRange",
];

export const WORKLOAD_KINDS: AppKind[] = [
  "deployment",
  "statefulSet",
  "daemonSet",
  "job",
  "cronJob",
];

export interface Resources {
  request: { memory: string; cpu: string };
  limit: { memory: string; cpu: string };
}

export interface MachineData {
  name: string;
  host: string;
}

export interface StoreData {
  machine: string;
  raftPort: number;
  clientPort: number;
  jvmFlags?: string[];
}

export interface RoleData {
  machine: string;
  port: number;
  jvmFlags?: string[];
  keyFile?: string;
}

export interface AgentData {
  machine: string;
  nodeId: string;
  gossipPort: number;
  labels: string[];
}

export interface TenantData {
  id: string;
  quota: { maxMemoryBytes: number; maxCpuMillicores: number; maxInstances: number };
  isolationPosture?: "OPEN" | "DENY_BY_DEFAULT";
}

export type Artifact = { source: "registry" } | { source: "jar"; path: string };

export interface WorkloadData {
  name: string;
  tenantId?: string;
  module: { name: string; version: string };
  artifact: Artifact;
  replicas?: number;
  placement?: { antiAffinity?: boolean; requiredLabels?: string[] };
  autoscale?: { minReplicas: number; maxReplicas: number; targetCpuUtilizationPercent: number };
  disruption?: { maxUnavailable: number; maxSurge?: number };
  activeDeadlineSeconds?: number;
  backoffLimit?: number;
  schedule?: string;
  concurrencyPolicy?: "Allow" | "Forbid" | "Replace";
  suspend?: boolean;
  resources: Resources;
}

export interface ServiceData {
  name: string;
  tenantId: string;
  port: number;
  targetPort: number;
  deploymentNames: string[];
}

export interface NetworkPolicyData {
  name: string;
  tenantId: string;
  deploymentNames?: string[];
  allowedCallerTenantIds?: string[];
}

export interface ConfigEntryData {
  tenantId: string;
  key: string;
  value: string;
}

export interface SecretData {
  tenantId: string;
  key: string;
}

export interface LimitRangeData {
  tenantId: string;
  min: { memory: string; cpu: string };
  max: { memory: string; cpu: string };
}

export type NodeData =
  | MachineData
  | StoreData
  | RoleData
  | AgentData
  | TenantData
  | WorkloadData
  | ServiceData
  | NetworkPolicyData
  | ConfigEntryData
  | SecretData
  | LimitRangeData;

export interface BlueprintNode {
  id: string;
  kind: NodeKind;
  position: { x: number; y: number };
  data: NodeData;
}

export type EdgeKind = "placedOn" | "belongsTo" | "fronts" | "allowsCaller" | "restricts";

export interface BlueprintEdge {
  id: string;
  kind: EdgeKind;
  source: string;
  target: string;
}

export interface Blueprint {
  id: string;
  name: string;
  version: string;
  transport: "plaintext" | "mtls";
  tlsMaterialDir?: string;
  runtime: { dataRoot: string; classpath?: string };
  nodes: BlueprintNode[];
  edges: BlueprintEdge[];
  updatedAt: string;
}

export const EDGE_LABELS: Record<EdgeKind, string> = {
  placedOn: "placed on",
  belongsTo: "belongs to",
  fronts: "fronts",
  allowsCaller: "allows caller",
  restricts: "restricts",
};

export const KIND_LABELS: Record<NodeKind, string> = {
  machine: "Machine",
  store: "Store",
  controlPlane: "Control plane",
  fafnir: "Fafnir",
  muninn: "Muninn",
  andvari: "Andvari",
  agent: "Agent",
  tenant: "Tenant",
  deployment: "Deployment",
  statefulSet: "StatefulSet",
  daemonSet: "DaemonSet",
  job: "Job",
  cronJob: "CronJob",
  service: "Service",
  networkPolicy: "NetworkPolicy",
  configEntry: "ConfigEntry",
  secret: "Secret",
  limitRange: "LimitRange",
};

export const isWorkload = (kind: NodeKind): boolean => (WORKLOAD_KINDS as string[]).includes(kind);

export const isPlacedRole = (kind: NodeKind): boolean =>
  ["store", "controlPlane", "fafnir", "muninn", "andvari", "agent"].includes(kind);

export const isTenantScoped = (kind: NodeKind): boolean =>
  isWorkload(kind) ||
  ["service", "networkPolicy", "configEntry", "secret", "limitRange"].includes(kind);

export const uid = (prefix: string): string =>
  `${prefix}-${Math.random().toString(36).slice(2, 8)}${Date.now().toString(36).slice(-3)}`;

const defaultResources = (): Resources => ({
  request: { memory: "64Mi", cpu: "50m" },
  limit: { memory: "256Mi", cpu: "500m" },
});

export function defaultDataFor(kind: NodeKind, seed: number = 1): NodeData {
  switch (kind) {
    case "machine":
      return { name: `machine-${seed}`, host: "127.0.0.1" } satisfies MachineData;
    case "store":
      return { machine: "", raftPort: 9080, clientPort: 9091 } satisfies StoreData;
    case "controlPlane":
      return { machine: "", port: 8080 } satisfies RoleData;
    case "fafnir":
      return { machine: "", port: 9092, keyFile: "~/.gimle/fafnir.key" } satisfies RoleData;
    case "muninn":
      return { machine: "", port: 9093 } satisfies RoleData;
    case "andvari":
      return { machine: "", port: 9094 } satisfies RoleData;
    case "agent":
      return {
        machine: "",
        nodeId: `node-${seed}`,
        gossipPort: 9090,
        labels: [],
      } satisfies AgentData;
    case "tenant":
      return {
        id: `tenant-${seed}`,
        quota: {
          maxMemoryBytes: 1024 * 1024 * 1024,
          maxCpuMillicores: 4000,
          maxInstances: 20,
        },
        isolationPosture: "DENY_BY_DEFAULT",
      } satisfies TenantData;
    case "deployment":
    case "statefulSet":
    case "daemonSet":
    case "job":
    case "cronJob": {
      const base: WorkloadData = {
        name: `${kind.toLowerCase()}-${seed}`,
        module: { name: "com.example.module", version: "1.0.0" },
        artifact: { source: "registry" },
        resources: defaultResources(),
      };
      if (kind === "deployment" || kind === "statefulSet") base.replicas = 1;
      if (kind === "cronJob") {
        base.schedule = "*/5 * * * *";
        base.concurrencyPolicy = "Forbid";
      }
      return base;
    }
    case "service":
      return {
        name: `service-${seed}`,
        tenantId: "",
        port: 80,
        targetPort: 8080,
        deploymentNames: [],
      } satisfies ServiceData;
    case "networkPolicy":
      return {
        name: `policy-${seed}`,
        tenantId: "",
        deploymentNames: [],
        allowedCallerTenantIds: [],
      } satisfies NetworkPolicyData;
    case "configEntry":
      return { tenantId: "", key: "some.key", value: "value" } satisfies ConfigEntryData;
    case "secret":
      return { tenantId: "", key: "some.token" } satisfies SecretData;
    case "limitRange":
      return {
        tenantId: "",
        min: { memory: "32Mi", cpu: "10m" },
        max: { memory: "512Mi", cpu: "1000m" },
      } satisfies LimitRangeData;
  }
}

export function createNode(
  kind: NodeKind,
  position: { x: number; y: number },
  seed = 1,
): BlueprintNode {
  return { id: uid(kind), kind, position, data: defaultDataFor(kind, seed) };
}

export const EDGE_RULES: Record<EdgeKind, { from: NodeKind[]; to: NodeKind[] }> = {
  placedOn: {
    from: ["store", "controlPlane", "fafnir", "muninn", "andvari", "agent"],
    to: ["machine"],
  },
  belongsTo: {
    from: [...WORKLOAD_KINDS, "service", "networkPolicy", "configEntry", "secret", "limitRange"],
    to: ["tenant"],
  },
  fronts: { from: ["service"], to: ["deployment", "statefulSet"] },
  allowsCaller: { from: ["networkPolicy"], to: ["tenant"] },
  restricts: { from: ["networkPolicy"], to: ["deployment"] },
};

export function edgeKindFor(sourceKind: NodeKind, targetKind: NodeKind): EdgeKind | null {
  if (isPlacedRole(sourceKind) && targetKind === "machine") return "placedOn";
  if (sourceKind === "service" && (targetKind === "deployment" || targetKind === "statefulSet"))
    return "fronts";
  if (sourceKind === "networkPolicy" && targetKind === "deployment") return "restricts";
  if (sourceKind === "networkPolicy" && targetKind === "tenant") return "allowsCaller";
  if (isTenantScoped(sourceKind) && targetKind === "tenant") return "belongsTo";
  return null;
}

export function createBlueprint(name: string, options?: { empty?: boolean }): Blueprint {
  if (options?.empty)
    return {
      id: uid("bp"),
      name,
      version: "0.1.0",
      transport: "plaintext",
      runtime: { dataRoot: "~/.gimle/data" },
      nodes: [],
      edges: [],
      updatedAt: new Date().toISOString(),
    };

  const machine: BlueprintNode = {
    id: uid("machine"),
    kind: "machine",
    position: { x: 40, y: 40 },
    data: { name: "local", host: "127.0.0.1" },
  };
  const store: BlueprintNode = {
    id: uid("store"),
    kind: "store",
    position: { x: 80, y: 120 },
    data: { machine: "local", raftPort: 9080, clientPort: 9091 },
  };
  const cp: BlueprintNode = {
    id: uid("controlPlane"),
    kind: "controlPlane",
    position: { x: 80, y: 200 },
    data: { machine: "local", port: 8080 },
  };
  const fafnir: BlueprintNode = {
    id: uid("fafnir"),
    kind: "fafnir",
    position: { x: 80, y: 280 },
    data: { machine: "local", port: 9092, keyFile: "~/.gimle/fafnir.key" },
  };
  const agent: BlueprintNode = {
    id: uid("agent"),
    kind: "agent",
    position: { x: 80, y: 360 },
    data: { machine: "local", nodeId: "node-1", gossipPort: 9090, labels: [] },
  };
  const nodes = [machine, store, cp, fafnir, agent];
  const edges: BlueprintEdge[] = [store, cp, fafnir, agent].map((n) => ({
    id: uid("edge"),
    kind: "placedOn" as const,
    source: n.id,
    target: machine.id,
  }));
  return {
    id: uid("bp"),
    name,
    version: "0.1.0",
    transport: "plaintext",
    runtime: { dataRoot: "~/.gimle/data" },
    nodes,
    edges,
    updatedAt: new Date().toISOString(),
  };
}
