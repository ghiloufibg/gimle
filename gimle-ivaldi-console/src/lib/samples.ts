import type { Blueprint, BlueprintEdge, BlueprintNode } from "./blueprint";

const edge = (kind: BlueprintEdge["kind"], source: string, target: string): BlueprintEdge => ({
  id: `e-${kind}-${source}-${target}`,
  kind,
  source,
  target,
});

function ordersPlatform(): Blueprint {
  const nodes: BlueprintNode[] = [
    { id: "m-local", kind: "machine", position: { x: 40, y: 40 }, data: { name: "local", host: "127.0.0.1" } },
    { id: "r-store", kind: "store", position: { x: 80, y: 140 }, data: { machine: "local", raftPort: 9080, clientPort: 9091 } },
    { id: "r-cp", kind: "controlPlane", position: { x: 300, y: 140 }, data: { machine: "local", port: 8080 } },
    { id: "r-fafnir", kind: "fafnir", position: { x: 80, y: 240 }, data: { machine: "local", port: 9092, keyFile: "~/.gimle/ivaldi/fafnir.key" } },
    { id: "r-muninn", kind: "muninn", position: { x: 300, y: 240 }, data: { machine: "local", port: 9093 } },
    { id: "r-andvari", kind: "andvari", position: { x: 520, y: 240 }, data: { machine: "local", port: 9094 } },
    { id: "r-agent1", kind: "agent", position: { x: 80, y: 340 }, data: { machine: "local", nodeId: "node-1", gossipPort: 9090, labels: ["ssd"] } },
    { id: "r-agent2", kind: "agent", position: { x: 300, y: 340 }, data: { machine: "local", nodeId: "node-2", gossipPort: 9095, labels: [] } },
    {
      id: "t-orders",
      kind: "tenant",
      position: { x: 840, y: 60 },
      data: {
        id: "orders-platform",
        quota: { maxMemoryBytes: 1024 * 1024 * 1024, maxCpuMillicores: 4000, maxInstances: 20 },
        isolationPosture: "DENY_BY_DEFAULT",
      },
    },
    {
      id: "a-limits",
      kind: "limitRange",
      position: { x: 1100, y: 60 },
      data: { tenantId: "orders-platform", min: { memory: "32Mi", cpu: "10m" }, max: { memory: "512Mi", cpu: "1000m" } },
    },
    {
      id: "a-web",
      kind: "deployment",
      position: { x: 840, y: 200 },
      data: {
        name: "web-ui-deployment",
        tenantId: "orders-platform",
        module: { name: "com.example.webui", version: "1.1.1" },
        artifact: { source: "registry" },
        replicas: 2,
        placement: { antiAffinity: true },
        resources: { request: { memory: "64Mi", cpu: "50m" }, limit: { memory: "256Mi", cpu: "500m" } },
      },
    },
    {
      id: "a-inventory",
      kind: "statefulSet",
      position: { x: 1100, y: 200 },
      data: {
        name: "inventory-service-statefulset",
        tenantId: "orders-platform",
        module: { name: "com.example.inventory", version: "1.0.0" },
        artifact: { source: "registry" },
        replicas: 1,
        resources: { request: { memory: "64Mi", cpu: "50m" }, limit: { memory: "256Mi", cpu: "500m" } },
      },
    },
    {
      id: "a-report",
      kind: "cronJob",
      position: { x: 1360, y: 200 },
      data: {
        name: "orders-report-cronjob",
        tenantId: "orders-platform",
        module: { name: "com.example.reporting", version: "1.0.0" },
        artifact: { source: "registry" },
        schedule: "*/5 * * * *",
        concurrencyPolicy: "Forbid",
        activeDeadlineSeconds: 60,
        resources: { request: { memory: "64Mi", cpu: "50m" }, limit: { memory: "256Mi", cpu: "500m" } },
      },
    },
    {
      id: "a-svc",
      kind: "service",
      position: { x: 840, y: 360 },
      data: { name: "web-ui", tenantId: "orders-platform", port: 80, targetPort: 8090, deploymentNames: ["web-ui-deployment"] },
    },
    {
      id: "a-np",
      kind: "networkPolicy",
      position: { x: 1100, y: 360 },
      data: { name: "web-ui-deny-cross-tenant", tenantId: "orders-platform", deploymentNames: ["web-ui-deployment"], allowedCallerTenantIds: [] },
    },
    {
      id: "a-cfg",
      kind: "configEntry",
      position: { x: 1360, y: 360 },
      data: { tenantId: "orders-platform", key: "greeting.prefix", value: "Hello" },
    },
    { id: "a-secret", kind: "secret", position: { x: 1360, y: 460 }, data: { tenantId: "orders-platform", key: "admin.token" } },
  ];

  const edges: BlueprintEdge[] = [
    ...["r-store", "r-cp", "r-fafnir", "r-muninn", "r-andvari", "r-agent1", "r-agent2"].map((id) =>
      edge("placedOn", id, "m-local"),
    ),
    ...["a-limits", "a-web", "a-inventory", "a-report", "a-svc", "a-np", "a-cfg", "a-secret"].map((id) =>
      edge("belongsTo", id, "t-orders"),
    ),
    edge("fronts", "a-svc", "a-web"),
    edge("restricts", "a-np", "a-web"),
  ];

  return {
    id: "sample-orders-platform-local",
    name: "orders-platform-local",
    version: "1.0.0",
    transport: "plaintext",
    runtime: { dataRoot: "~/.gimle/ivaldi/data" },
    nodes,
    edges,
    updatedAt: new Date().toISOString(),
  };
}

function brokenExample(): Blueprint {
  const nodes: BlueprintNode[] = [
    { id: "bm-1", kind: "machine", position: { x: 40, y: 40 }, data: { name: "box", host: "127.0.0.1" } },
    { id: "bs-1", kind: "store", position: { x: 80, y: 140 }, data: { machine: "box", raftPort: 9080, clientPort: 9091 } },
    { id: "bc-1", kind: "controlPlane", position: { x: 300, y: 140 }, data: { machine: "box", port: 8080 } },
    { id: "bc-2", kind: "controlPlane", position: { x: 520, y: 140 }, data: { machine: "box", port: 8080 } },
    { id: "bf-1", kind: "fafnir", position: { x: 80, y: 240 }, data: { machine: "box", port: 9092, keyFile: "~/.gimle/fafnir.key" } },
    { id: "ba-1", kind: "agent", position: { x: 300, y: 240 }, data: { machine: "box", nodeId: "node-1", gossipPort: 9090, labels: [] } },
    {
      id: "bt-1",
      kind: "tenant",
      position: { x: 840, y: 60 },
      data: { id: "demo", quota: { maxMemoryBytes: 512 * 1024 * 1024, maxCpuMillicores: 2000, maxInstances: 8 } },
    },
    {
      id: "bd-1",
      kind: "deployment",
      position: { x: 840, y: 200 },
      data: {
        name: "api-deployment",
        tenantId: "demo",
        module: { name: "com.example.api", version: "0.9.0" },
        artifact: { source: "jar", path: "build/libs/api.jar" },
        replicas: 2,
        resources: { request: { memory: "512Mi", cpu: "900m" }, limit: { memory: "128Mi", cpu: "200m" } },
      },
    },
    {
      id: "bsv-1",
      kind: "service",
      position: { x: 1100, y: 200 },
      data: { name: "api", tenantId: "demo", port: 80, targetPort: 8080, deploymentNames: ["missing-deployment"] },
    },
  ];

  const edges: BlueprintEdge[] = [
    ...["bs-1", "bc-1", "bc-2", "bf-1", "ba-1"].map((id) => edge("placedOn", id, "bm-1")),
    ...["bd-1", "bsv-1"].map((id) => edge("belongsTo", id, "bt-1")),
  ];

  return {
    id: "sample-broken-example",
    name: "broken-example",
    version: "0.0.1",
    transport: "mtls",
    runtime: { dataRoot: "~/.gimle/broken/data" },
    nodes,
    edges,
    updatedAt: new Date().toISOString(),
  };
}

export function sampleBlueprints(): Blueprint[] {
  return [ordersPlatform(), brokenExample()];
}
