import { stringify } from "yaml";

import {
  type AgentData,
  type Blueprint,
  type BlueprintNode,
  type ConfigEntryData,
  type LimitRangeData,
  type MachineData,
  type NetworkPolicyData,
  type RoleData,
  type SecretData,
  type ServiceData,
  type StoreData,
  type TenantData,
  type WorkloadData,
} from "./blueprint";
import { DEFAULT_PORTS } from "./ports";
import { nodesOf, tenantIdOf } from "./rules";

export interface RenderedFile {
  path: string;
  content: string;
}

const yml = (value: unknown): string =>
  stringify(value, { indent: 2, lineWidth: 0 })
    .split("\n")
    .map((l) => l.replace(/\s+$/, ""))
    .join("\n");

const pad = (n: number): string => String(n).padStart(2, "0");

const MANIFEST_KIND: Record<string, string> = {
  deployment: "Deployment",
  statefulSet: "StatefulSet",
  daemonSet: "DaemonSet",
  job: "Job",
  cronJob: "CronJob",
};

export function orderedWorkloads(bp: Blueprint): BlueprintNode[] {
  const order = ["statefulSet", "daemonSet", "deployment", "job", "cronJob"];
  return order.flatMap((kind) => nodesOf(bp, kind));
}

function renderTopology(bp: Blueprint): string {
  const doc: Record<string, unknown> = { name: bp.name };
  if (bp.transport === "mtls") {
    doc.transport = "mtls";
    if (bp.tlsMaterialDir) doc.tls = { materialDir: bp.tlsMaterialDir };
  }
  doc.machines = nodesOf(bp, "machine").map((m) => {
    const d = m.data as MachineData;
    return { name: d.name, host: d.host };
  });
  const runtime: Record<string, unknown> = { dataRoot: bp.runtime.dataRoot };
  if (bp.runtime.classpath) runtime.classpath = bp.runtime.classpath;
  doc.runtime = runtime;

  const stores = nodesOf(bp, "store");
  if (stores.length)
    doc.store = {
      replicas: stores.map((s) => {
        const d = s.data as StoreData;
        const r: Record<string, unknown> = { machine: d.machine };
        if (d.raftPort !== DEFAULT_PORTS.storeRaft) r.raftPort = d.raftPort;
        if (d.clientPort !== DEFAULT_PORTS.storeClient) r.clientPort = d.clientPort;
        return r;
      }),
    };

  const roleBlock = (kind: string, defaultPort: number) => {
    const list = nodesOf(bp, kind);
    if (!list.length) return undefined;
    return list.map((n) => {
      const d = n.data as RoleData;
      const r: Record<string, unknown> = { machine: d.machine };
      if (d.port !== defaultPort) r.port = d.port;
      return r;
    });
  };

  const cp = roleBlock("controlPlane", DEFAULT_PORTS.controlPlane);
  if (cp) doc.controlPlane = { replicas: cp };
  const fafnirNodes = nodesOf(bp, "fafnir");
  if (fafnirNodes.length) {
    const keyFile = (fafnirNodes[0].data as RoleData).keyFile;
    doc.fafnir = {
      ...(keyFile ? { keyFile } : {}),
      replicas: roleBlock("fafnir", DEFAULT_PORTS.fafnir),
    };
  }
  const muninn = roleBlock("muninn", DEFAULT_PORTS.muninn);
  if (muninn) doc.muninn = { replicas: muninn };
  const andvari = roleBlock("andvari", DEFAULT_PORTS.andvari);
  if (andvari) doc.andvari = { replicas: andvari };

  const agents = nodesOf(bp, "agent");
  if (agents.length)
    doc.agents = agents.map((a) => {
      const d = a.data as AgentData;
      const r: Record<string, unknown> = { machine: d.machine, nodeId: d.nodeId };
      if (d.gossipPort !== DEFAULT_PORTS.agent) r.gossipPort = d.gossipPort;
      if (d.labels?.length) r.labels = [...d.labels].sort();
      return r;
    });

  const jvm: Record<string, string[]> = {};
  for (const n of bp.nodes) {
    const flags = (n.data as { jvmFlags?: string[] }).jvmFlags;
    if (flags?.length) jvm[n.kind] = flags;
  }
  if (Object.keys(jvm).length) doc.jvm = jvm;

  return yml(doc);
}

function workloadDoc(bp: Blueprint, node: BlueprintNode): Record<string, unknown> {
  const d = node.data as WorkloadData;
  const doc: Record<string, unknown> = {};
  const jar = d.artifact?.source === "jar";
  if (!jar) doc.apiVersion = "v1";
  doc.kind = MANIFEST_KIND[node.kind];
  doc.name = d.name;
  const tenantId = tenantIdOf(bp, node);
  if (tenantId) doc.tenantId = tenantId;
  if (node.kind === "cronJob") {
    doc.schedule = d.schedule;
    doc.concurrencyPolicy = d.concurrencyPolicy;
    if (d.suspend) doc.suspend = true;
    const tpl: Record<string, unknown> = {
      module: { name: d.module.name, version: d.module.version },
    };
    if (d.activeDeadlineSeconds !== undefined) tpl.activeDeadlineSeconds = d.activeDeadlineSeconds;
    if (d.backoffLimit !== undefined) tpl.backoffLimit = d.backoffLimit;
    doc.jobTemplate = tpl;
  } else {
    doc.module = { name: d.module.name, version: d.module.version };
    if (node.kind === "job") {
      if (d.activeDeadlineSeconds !== undefined)
        doc.activeDeadlineSeconds = d.activeDeadlineSeconds;
      if (d.backoffLimit !== undefined) doc.backoffLimit = d.backoffLimit;
    }
    if (node.kind === "deployment" || node.kind === "statefulSet") doc.replicas = d.replicas ?? 1;
  }
  if (jar && d.artifact.source === "jar") doc.artifactPath = d.artifact.path;
  if (d.placement && (d.placement.antiAffinity || d.placement.requiredLabels?.length)) {
    const pl: Record<string, unknown> = {};
    if (d.placement.antiAffinity) pl.antiAffinity = true;
    if (d.placement.requiredLabels?.length)
      pl.requiredLabels = [...d.placement.requiredLabels].sort();
    doc.placement = pl;
  }
  if (d.autoscale && node.kind === "deployment") doc.autoscale = { ...d.autoscale };
  if (d.disruption) {
    const dis: Record<string, unknown> = { maxUnavailable: d.disruption.maxUnavailable };
    if (node.kind !== "daemonSet" && d.disruption.maxSurge !== undefined)
      dis.maxSurge = d.disruption.maxSurge;
    doc.disruption = dis;
  }
  return doc;
}

export function renderFiles(bp: Blueprint): RenderedFile[] {
  const files: RenderedFile[] = [{ path: "topology.yaml", content: renderTopology(bp) }];

  let index = 1;
  const manifestPaths: string[] = [];

  for (const w of orderedWorkloads(bp)) {
    const d = w.data as WorkloadData;
    const path = `manifests/${pad(index++)}-${d.name || "unnamed"}.yaml`;
    manifestPaths.push(path);
    files.push({ path, content: yml(workloadDoc(bp, w)) });
  }

  for (const s of nodesOf(bp, "service")) {
    const d = s.data as ServiceData;
    const path = `manifests/${pad(index++)}-service-${d.name || "unnamed"}.yaml`;
    manifestPaths.push(path);
    const fronted = bp.edges
      .filter((e) => e.kind === "fronts" && e.source === s.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as WorkloadData).name);
    const names = [...new Set([...(d.deploymentNames ?? []), ...fronted])].sort();
    files.push({
      path,
      content: yml({
        kind: "Service",
        name: d.name,
        tenantId: tenantIdOf(bp, s) ?? d.tenantId,
        deploymentNames: names,
        port: d.port,
        targetPort: d.targetPort,
      }),
    });
  }

  for (const np of nodesOf(bp, "networkPolicy")) {
    const d = np.data as NetworkPolicyData;
    const path = `manifests/${pad(index++)}-networkpolicy-${d.name || "unnamed"}.yaml`;
    manifestPaths.push(path);
    const restricted = bp.edges
      .filter((e) => e.kind === "restricts" && e.source === np.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as WorkloadData).name);
    const callers = bp.edges
      .filter((e) => e.kind === "allowsCaller" && e.source === np.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as TenantData).id);
    const doc: Record<string, unknown> = {
      kind: "NetworkPolicy",
      name: d.name,
      tenantId: tenantIdOf(bp, np) ?? d.tenantId,
    };
    const deployments = [...new Set([...(d.deploymentNames ?? []), ...restricted])].sort();
    if (deployments.length) doc.deploymentNames = deployments;
    const allowed = [...new Set([...(d.allowedCallerTenantIds ?? []), ...callers])].sort();
    if (allowed.length) doc.allowedCallerTenantIds = allowed;
    files.push({ path, content: yml(doc) });
  }

  // LimitRange is not a Bundle-applied resource -- gimle-hilmir's own BundleParser has no
  // "LimitRange" workload kind, so this file is deliberately kept out of manifestPaths (and
  // therefore out of bundle.workloads[]) even though it lives alongside the other manifests. It's
  // a standalone control-plane resource (PUT /limitranges/{tenantId}), applied by the run itself
  // outside the bundle deploy, or by hand via `gimle apply -f` for the "download and run" path.
  const limitRangeFiles: { path: string; tenantId: string }[] = [];
  for (const lr of nodesOf(bp, "limitRange")) {
    const d = lr.data as LimitRangeData;
    const tenantId = tenantIdOf(bp, lr) ?? d.tenantId;
    const path = `manifests/${pad(index++)}-limitrange-${tenantId || "unnamed"}.yaml`;
    limitRangeFiles.push({ path, tenantId });
    files.push({
      path,
      content: yml({
        kind: "LimitRange",
        name: tenantId,
        minRequest: { memory: d.min.memory, cpu: d.min.cpu },
        maxRequest: { memory: d.max.memory, cpu: d.max.cpu },
      }),
    });
  }

  const secrets = nodesOf(bp, "secret").map((s) => ({
    node: s,
    data: s.data as SecretData,
  }));
  const values: Record<string, string> = {};
  for (const s of [...secrets].sort((a, b) => a.data.key.localeCompare(b.data.key)))
    values[s.data.key] = "";

  const bundle: Record<string, unknown> = {
    kind: "Bundle",
    name: bp.name,
    version: bp.version,
    values,
    tenants: nodesOf(bp, "tenant").map((t) => {
      const d = t.data as TenantData;
      return { id: d.id, quota: { ...d.quota } };
    }),
    config: nodesOf(bp, "configEntry").map((c) => {
      const d = c.data as ConfigEntryData;
      return { tenant: tenantIdOf(bp, c) ?? d.tenantId, key: d.key, value: d.value };
    }),
    secrets: secrets.map(({ node, data }) => ({
      tenant: tenantIdOf(bp, node) ?? data.tenantId,
      key: data.key,
      value: `\${values.${data.key}}`,
    })),
    workloads: manifestPaths.map((file) => ({ file })),
  };
  files.push({ path: "bundle.yaml", content: yml(bundle) });
  files.push({ path: "values.example.yaml", content: yml(values) });
  files.push({ path: "README.md", content: renderReadme(bp, limitRangeFiles) });
  files.push({
    path: "ivaldi.blueprint.json",
    content: `${JSON.stringify(bp, null, 2)}\n`,
  });

  return files;
}

export function controlPlanePort(bp: Blueprint): number {
  const cp = nodesOf(bp, "controlPlane")[0];
  return cp ? (cp.data as RoleData).port : DEFAULT_PORTS.controlPlane;
}

export function firstMachineName(bp: Blueprint): string {
  const m = nodesOf(bp, "machine")[0];
  return m ? (m.data as MachineData).name : "local";
}

function renderReadme(
  bp: Blueprint,
  limitRangeFiles: { path: string; tenantId: string }[],
): string {
  const machine = firstMachineName(bp);
  const port = controlPlanePort(bp);
  const jars = orderedWorkloads(bp)
    .map((w) => w.data as WorkloadData)
    .filter((d) => d.artifact?.source === "jar");
  const pushes = jars.length
    ? jars
        .map(
          (d) =>
            `gimle artifact push ${d.artifact.source === "jar" ? d.artifact.path : ""} --server 127.0.0.1:${port}`,
        )
        .join("\n")
    : "# no jar-sourced workloads";
  const limitRangeApplies = limitRangeFiles.length
    ? limitRangeFiles.map((f) => `gimle apply -f ${f.path} --server 127.0.0.1:${port}`).join("\n")
    : "# no limit ranges to apply";

  return `# ${bp.name}

Generated by Ivaldi. Version ${bp.version}.

## 1. Validate the topology

\`\`\`sh
hilmir validate -f topology.yaml
\`\`\`

## 2. Bring the cluster up

\`\`\`sh
hilmir up -f topology.yaml --machine ${machine}
\`\`\`

## 3. Push local artifacts

\`\`\`sh
${pushes}
\`\`\`

## 3b. Apply limit ranges

\`\`\`sh
${limitRangeApplies}
\`\`\`

## 4. Deploy the bundle

\`\`\`sh
cp values.example.yaml values.yaml   # fill in the secret values
hilmir deploy -f bundle.yaml --values values.yaml --server 127.0.0.1:${port} --wait
\`\`\`

## 5. Console

http://127.0.0.1:${port}/console

## 6. Tear down

\`\`\`sh
hilmir down --machine ${machine} --data-root ${bp.runtime.dataRoot}
\`\`\`
`;
}
