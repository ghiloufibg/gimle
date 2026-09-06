import { Scalar, stringify } from "yaml";

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
import { machineNameOf, tenantIdOf } from "./effective";
import { DEFAULT_PORTS } from "./ports";
import { nodesOf } from "./rules";

export interface RenderedFile {
  path: string;
  content: string;
}

// A YAML 1.2 emitter leaves these bare because 1.2 reads them as plain strings, but the platform
// and plenty of other readers are on 1.1, where a bare `yes` is a boolean and a bare 2026-01-01 is
// a date. Quoting them keeps a config value the user typed as a string a string everywhere.
const YAML_1_1_AMBIGUOUS = /^(y|n|yes|no|on|off|\d{4}-\d{2}-\d{2}([Tt ].*)?)$/i;

function quoteAmbiguous(value: unknown): unknown {
  if (typeof value === "string")
    return YAML_1_1_AMBIGUOUS.test(value)
      ? Object.assign(new Scalar(value), { type: Scalar.QUOTE_DOUBLE })
      : value;
  if (Array.isArray(value)) return value.map(quoteAmbiguous);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, quoteAmbiguous(v)]),
    );
  return value;
}

const yml = (value: unknown): string =>
  stringify(quoteAmbiguous(value), { indent: 2, lineWidth: 0 })
    .split("\n")
    .map((l) => l.replace(/\s+$/, ""))
    .join("\n");

const pad = (n: number): string => String(n).padStart(2, "0");

/**
 * A resource's name spliced straight into a path makes a filename nobody can extract: a "/" opens
 * a directory inside the zip, a quote or backslash breaks the entry on Windows, and a long name
 * exceeds every filesystem's per-component limit. The name inside the manifest is untouched --
 * only the path it is written to is sanitised, the same way the blueprint's own download filename
 * already is.
 */
function fileSlug(name: string): string {
  const slug = name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, "-")
    .replace(/^[-.]+|[-.]+$/g, "")
    .slice(0, 60);
  return slug || "unnamed";
}

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
  const machineOf = (n: BlueprintNode) => machineNameOf(bp, n) ?? "";
  const runtime: Record<string, unknown> = { dataRoot: bp.runtime.dataRoot };
  if (bp.runtime.classpath) runtime.classpath = bp.runtime.classpath;
  doc.runtime = runtime;

  const stores = nodesOf(bp, "store");
  if (stores.length)
    doc.store = {
      replicas: stores.map((s) => {
        const d = s.data as StoreData;
        const r: Record<string, unknown> = { machine: machineOf(s) };
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
      const r: Record<string, unknown> = { machine: machineOf(n) };
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
      const r: Record<string, unknown> = { machine: machineOf(a), nodeId: d.nodeId };
      if (d.gossipPort !== DEFAULT_PORTS.agent) r.gossipPort = d.gossipPort;
      if (d.labels?.length) r.labels = [...d.labels].sort();
      return r;
    });

  // topology.yaml's `jvm:` is one flag list per role, not per replica, so two replicas of a role
  // that each carry flags contribute to the same list. Unioned rather than overwritten: dropping
  // one replica's flags on the floor would leave the canvas and the rendered file disagreeing with
  // nothing said about it. rules.ts warns when two replicas of a role disagree.
  const jvm: Record<string, string[]> = {};
  for (const n of bp.nodes) {
    const flags = (n.data as { jvmFlags?: string[] }).jvmFlags;
    if (!flags?.length) continue;
    jvm[n.kind] = [...new Set([...(jvm[n.kind] ?? []), ...flags])];
  }
  if (Object.keys(jvm).length) doc.jvm = jvm;

  return yml(doc);
}

function workloadDoc(bp: Blueprint, node: BlueprintNode): Record<string, unknown> {
  const d = node.data as WorkloadData;
  // Every workload is v1 now that none carries a local artifactPath: the schema that rejects the
  // field is the schema every manifest rendered here already satisfies.
  const doc: Record<string, unknown> = { apiVersion: "v1" };
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
  // Deliberately no artifactPath. The platform deprecates the field -- it is resolved against the
  // reading process's own working directory, so it cannot survive being applied from anywhere but
  // the machine that rendered it -- and both paths out of this file set push the jar to the
  // artifact registry first (the run itself, and the README's own step 3), which is exactly what
  // a module coordinate with no path resolves through. Which jar backs which manifest is recorded
  // in ivaldi.artifacts.yaml instead: Ivaldi's own bookkeeping, kept out of a platform manifest.
  if (d.placement && (d.placement.antiAffinity || d.placement.requiredLabels?.length)) {
    const pl: Record<string, unknown> = {};
    if (d.placement.antiAffinity) pl.antiAffinity = true;
    if (d.placement.requiredLabels?.length)
      pl.requiredLabels = [...d.placement.requiredLabels].sort();
    // A CronJob's placement applies to the Jobs it spawns, so it lives under jobTemplate -- at the
    // top level the parser does not recognise the field and drops it, which made two inspector
    // controls that visibly changed the file change nothing in the cluster.
    if (node.kind === "cronJob") (doc.jobTemplate as Record<string, unknown>).placement = pl;
    else doc.placement = pl;
  }
  if (d.autoscale && (node.kind === "deployment" || node.kind === "statefulSet"))
    doc.autoscale = { ...d.autoscale };
  // Deliberately no `resources` here. A module's request/limit lives in its own gimle-module.yaml
  // inside the jar, and the platform's manifest parser answers a resources key on a workload with
  // "not a recognized field for this manifest kind and was ignored" -- emitting it would put a
  // tier-2 warning on every workload while changing nothing that gets deployed. The values on the
  // node exist for tier-1 quota and limit-range arithmetic, which has to run before any jar does.
  if (d.disruption) {
    const dis: Record<string, unknown> = { maxUnavailable: d.disruption.maxUnavailable };
    if (node.kind === "deployment" && d.disruption.maxSurge !== undefined)
      dis.maxSurge = d.disruption.maxSurge;
    doc.disruption = dis;
  }
  if (node.kind === "daemonSet" && d.tolerateAllTaints) doc.tolerateAllTaints = true;
  return doc;
}

export function renderFiles(bp: Blueprint): RenderedFile[] {
  const files: RenderedFile[] = [{ path: "topology.yaml", content: renderTopology(bp) }];

  let index = 1;
  // bundle.workloads[] carries the five workload kinds and nothing else: gimle-hilmir's own
  // BundleApplier maps a workload's kind: to a control-plane path prefix and knows only those
  // five, so a Service, NetworkPolicy or LimitRange listed there fails the deploy outright.
  // Those three are standalone control-plane resources instead -- applied by the run itself
  // before the bundle deploy, or by hand via `gimle apply -f` on the download-and-run path.
  const manifestPaths: string[] = [];
  const standalonePaths: string[] = [];

  for (const w of orderedWorkloads(bp)) {
    const d = w.data as WorkloadData;
    const path = `manifests/${pad(index++)}-${fileSlug(d.name)}.yaml`;
    manifestPaths.push(path);
    files.push({ path, content: yml(workloadDoc(bp, w)) });
  }

  for (const s of nodesOf(bp, "service")) {
    const d = s.data as ServiceData;
    const path = `manifests/${pad(index++)}-service-${fileSlug(d.name)}.yaml`;
    standalonePaths.push(path);
    const fronted = bp.edges
      .filter((e) => e.kind === "fronts" && e.source === s.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as WorkloadData).name);
    const names = [...new Set([...(d.deploymentNames ?? []), ...fronted])].sort();
    const serviceDoc: Record<string, unknown> = {
      kind: "Service",
      name: d.name,
      tenantId: tenantIdOf(bp, s) ?? d.tenantId,
      deploymentNames: names,
      port: d.port,
    };
    // Omitted rather than written as null/undefined when blank -- the platform's own
    // ServiceSpec.targetPort is an OptionalInt that then defaults to `port`, and an explicit key
    // here would fail to parse as the number the manifest schema expects.
    if (d.targetPort !== undefined) serviceDoc.targetPort = d.targetPort;
    files.push({
      path,
      content: yml(serviceDoc),
    });
  }

  for (const np of nodesOf(bp, "networkPolicy")) {
    const d = np.data as NetworkPolicyData;
    const path = `manifests/${pad(index++)}-networkpolicy-${fileSlug(d.name)}.yaml`;
    standalonePaths.push(path);
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
    // Always emitted, empty included: an empty allowed-caller list is the deny-every-cross-tenant
    // -caller policy, and the platform requires a policy to restrict at least one direction --
    // omitting the key turned that deliberate deny-all into a document the cluster refuses.
    doc.allowedCallerTenantIds = [
      ...new Set([...(d.allowedCallerTenantIds ?? []), ...callers]),
    ].sort();
    files.push({ path, content: yml(doc) });
  }

  for (const lr of nodesOf(bp, "limitRange")) {
    const d = lr.data as LimitRangeData;
    const tenantId = tenantIdOf(bp, lr) ?? d.tenantId;
    const path = `manifests/${pad(index++)}-limitrange-${fileSlug(tenantId)}.yaml`;
    standalonePaths.push(path);
    files.push({
      path,
      content: yml({
        kind: "LimitRange",
        name: tenantId,
        // A bound block is emitted only when both of its halves are filled in: the platform reads
        // a present block as complete and refuses one carrying a blank, so a half-filled bound
        // rendered a manifest that could never be applied.
        ...(d.min?.memory?.trim() && d.min?.cpu?.trim()
          ? { minRequest: { memory: d.min.memory, cpu: d.min.cpu } }
          : {}),
        ...(d.max?.memory?.trim() && d.max?.cpu?.trim()
          ? { maxRequest: { memory: d.max.memory, cpu: d.max.cpu } }
          : {}),
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
    // The release is named after the blueprint's id, which never changes, rather than its name,
    // which does: renaming a blueprint forked the release history, so the next run deployed a
    // fresh release beside the old one and prune-on-upgrade was computed against the new history
    // -- leaving whatever the old release created running and untracked.
    name: releaseNameOf(bp),
    version: bp.version,
    values,
    tenants: nodesOf(bp, "tenant").map((t) => {
      const d = t.data as TenantData;
      return {
        id: d.id,
        quota: { ...d.quota },
        ...(d.isolationPosture ? { isolationPosture: d.isolationPosture } : {}),
      };
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
  // Ivaldi's own record of which local jar backs each rendered manifest's module coordinate. The
  // manifests themselves carry no path (see workloadDoc), so this is what tells a run -- and the
  // reader of a downloaded file set -- what has to reach the registry before the bundle deploys.
  const jarArtifacts = manifestPaths
    .map((file, i) => ({ file, data: orderedWorkloads(bp)[i].data as WorkloadData }))
    .filter(({ data }) => data.artifact?.source === "jar")
    .map(({ file, data }) => ({
      manifest: file,
      module: data.module.name,
      version: data.module.version,
      path: data.artifact.source === "jar" ? data.artifact.path : "",
    }));
  if (jarArtifacts.length)
    files.push({ path: "ivaldi.artifacts.yaml", content: yml({ artifacts: jarArtifacts }) });
  files.push({ path: "bundle.yaml", content: yml(bundle) });
  files.push({ path: "values.example.yaml", content: yml(values) });
  files.push({ path: "README.md", content: renderReadme(bp, standalonePaths) });
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

/** The stable release name for a blueprint: its id, or its name for one never yet stored. */
export function releaseNameOf(bp: Blueprint): string {
  return bp.id?.trim() || bp.name;
}

export function firstMachineHost(bp: Blueprint): string {
  const m = nodesOf(bp, "machine")[0];
  return m ? (m.data as MachineData).host : "127.0.0.1";
}

export function firstMachineName(bp: Blueprint): string {
  const m = nodesOf(bp, "machine")[0];
  return m ? (m.data as MachineData).name : "local";
}

function renderReadme(bp: Blueprint, standalonePaths: string[]): string {
  // Every machine, not just the first: `hilmir up` brings up one machine's own processes, so a
  // two-machine topology needs the command twice -- and a teardown that names only the first
  // leaves the other machine's processes running and its data behind.
  const machines = nodesOf(bp, "machine").map((m) => (m.data as MachineData).name);
  const port = controlPlanePort(bp);
  // Under mTLS every leaf certificate is minted for the machine's declared hostname (an IP
  // literal is refused outright by the topology rules), so an IP here would fail SAN matching
  // even once the scheme is right.
  const mtls = bp.transport === "mtls";
  const host = mtls ? firstMachineHost(bp) : "127.0.0.1";
  const scheme = mtls ? "https" : "http";
  const server = `${host}:${port}`;
  // Every client below has to speak the same transport as the cluster, and neither bin/gimle nor
  // bin/hilmir forwards -D flags to the JVM, so the properties go through JAVA_TOOL_OPTIONS.
  const tlsEnv = mtls
    ? `export JAVA_TOOL_OPTIONS="-Dgimle.transport.protocol=tls \\
  -Dgimle.tls.certFile=${bp.tlsMaterialDir ?? "<tls-dir>"}/operator.crt \\
  -Dgimle.tls.keyFile=${bp.tlsMaterialDir ?? "<tls-dir>"}/operator.key \\
  -Dgimle.tls.caFile=${bp.tlsMaterialDir ?? "<tls-dir>"}/ca.crt"

`
    : "";
  const pkiStep = mtls
    ? `## 1b. Mint the TLS material

\`hilmir up\` does not mint certificates; every process reads them from the material directory
and refuses to start without them.

\`\`\`sh
hilmir pki init -f topology.yaml
\`\`\`

`
    : "";
  const jars = orderedWorkloads(bp)
    .map((w) => w.data as WorkloadData)
    .filter((d) => d.artifact?.source === "jar");
  const pushes = jars.length
    ? jars
        .map(
          (d) =>
            `gimle artifact push ${d.artifact.source === "jar" ? d.artifact.path : ""} --server ${server}`,
        )
        .join("\n")
    : "# no jar-sourced workloads";
  const standaloneApplies = standalonePaths.length
    ? standalonePaths.map((path) => `gimle apply -f ${path} --server ${server}`).join("\n")
    : "# no standalone resources to apply";

  return `# ${bp.name}

Generated by Ivaldi. Version ${bp.version}.

Every command below is \`hilmir\` or \`gimle\` from the **gimle-platform** distribution: the
processes they spawn inherit the launching process's own classpath, and only that archive carries
every platform module. The \`gimle-hilmir\` archive ships the launcher without them, so a cluster
started from it dies on the first process with a \`NoClassDefFoundError\`.

## 1. Validate the topology

\`\`\`sh
hilmir validate -f topology.yaml
\`\`\`

${pkiStep}## 2. Bring the cluster up

\`\`\`sh
${machines.map((m) => `hilmir up -f topology.yaml --machine ${m}`).join("\n")}
\`\`\`

## 3. Push local artifacts

\`\`\`sh
${tlsEnv}${pushes}
\`\`\`

## 3b. Apply the standalone resources

Services, network policies and limit ranges are control-plane resources in their own right, not
bundle workloads, so they are applied directly rather than through \`hilmir deploy\`.

\`\`\`sh
${standaloneApplies}
\`\`\`

## 4. Deploy the bundle

\`\`\`sh
cp values.example.yaml values.yaml   # fill in the secret values
hilmir deploy -f bundle.yaml --values values.yaml --server ${server} --wait
\`\`\`

## 5. Console

${scheme}://${server}/console

## 6. Tear down

\`\`\`sh
${machines
  .map((m) => `hilmir down --machine ${m} --data-root ${bp.runtime.dataRoot}`)
  .reverse()
  .join("\n")}
\`\`\`
`;
}
