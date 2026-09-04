import {
  isWorkload,
  type AgentData,
  type Blueprint,
  type BlueprintNode,
  type ConfigEntryData,
  type LimitRangeData,
  type MachineData,
  type NetworkPolicyData,
  type Problem,
  type RoleData,
  type SecretData,
  type ServiceData,
  type StoreData,
  type TenantData,
  type WorkloadData,
} from "./blueprint";
import { portConflicts } from "./ports";
import { parseCpu, parseMemory } from "./units";

const err = (code: string, message: string, nodeId?: string): Problem => ({
  code,
  severity: "error",
  message,
  nodeId,
});
const warn = (code: string, message: string, nodeId?: string): Problem => ({
  code,
  severity: "warning",
  message,
  nodeId,
});
const info = (code: string, message: string, nodeId?: string): Problem => ({
  code,
  severity: "info",
  message,
  nodeId,
});

export function nodesOf(bp: Blueprint, kind: string): BlueprintNode[] {
  return bp.nodes.filter((n) => n.kind === kind);
}

export function machineNames(bp: Blueprint): string[] {
  return nodesOf(bp, "machine").map((n) => (n.data as MachineData).name);
}

export function tenantIdOf(bp: Blueprint, node: BlueprintNode): string | undefined {
  const explicit = (node.data as { tenantId?: string }).tenantId;
  if (explicit) return explicit;
  const edge = bp.edges.find((e) => e.kind === "belongsTo" && e.source === node.id);
  if (!edge) return undefined;
  const tenant = bp.nodes.find((n) => n.id === edge.target);
  return tenant ? (tenant.data as TenantData).id : undefined;
}

export function workloadNodes(bp: Blueprint): BlueprintNode[] {
  return bp.nodes.filter((n) => isWorkload(n.kind));
}

export function validateTopology(bp: Blueprint): Problem[] {
  const p: Problem[] = [];
  const machines = nodesOf(bp, "machine");
  const names = machines.map((m) => (m.data as MachineData).name);

  if (machines.length === 0) p.push(err("NO_MACHINES", "Topology declares no machines."));
  if (nodesOf(bp, "store").length === 0) p.push(err("NO_STORE", "No store role declared."));
  if (nodesOf(bp, "controlPlane").length === 0)
    p.push(err("NO_CONTROL_PLANE", "No control plane role declared."));
  if (nodesOf(bp, "fafnir").length === 0) p.push(err("NO_FAFNIR", "No fafnir role declared."));

  const seenMachine = new Set<string>();
  for (const m of machines) {
    const name = (m.data as MachineData).name;
    if (seenMachine.has(name))
      p.push(err("DUPLICATE_MACHINE", `Machine name "${name}" is declared twice.`, m.id));
    seenMachine.add(name);
  }

  const seenId = new Set<string>();
  for (const n of bp.nodes) {
    if (seenId.has(n.id)) p.push(err("DUPLICATE_NODE_ID", `Duplicate node id "${n.id}".`, n.id));
    seenId.add(n.id);
  }

  for (const n of bp.nodes) {
    if (["store", "controlPlane", "fafnir", "muninn", "andvari", "agent"].includes(n.kind)) {
      const machine = (n.data as { machine?: string }).machine ?? "";
      if (!machine || !names.includes(machine))
        p.push(err("UNKNOWN_MACHINE", `${n.kind} is not placed on a known machine.`, n.id));
    }
  }

  for (const group of portConflicts(bp)) {
    const [first] = group;
    for (const c of group)
      p.push(
        err(
          "PORT_CONFLICT",
          `Port ${first.port} on machine "${first.machine || "?"}" is claimed by ${group
            .map((g) => g.what)
            .join(" and ")}.`,
          c.nodeId,
        ),
      );
  }

  const many = machines.length > 1;
  const groupBy = (kind: string) => {
    const byMachine = new Map<string, BlueprintNode[]>();
    for (const n of nodesOf(bp, kind)) {
      const m = (n.data as { machine?: string }).machine ?? "";
      byMachine.set(m, [...(byMachine.get(m) ?? []), n]);
    }
    return byMachine;
  };

  for (const [machine, list] of groupBy("store")) {
    if (list.length > 1)
      for (const n of list)
        p.push(
          (many ? err : warn)(
            "REPLICAS_COLOCATED",
            `Multiple store replicas share machine "${machine}".`,
            n.id,
          ),
        );
  }
  for (const [machine, list] of groupBy("controlPlane")) {
    if (list.length > 1)
      for (const n of list)
        p.push(
          (many ? err : warn)(
            "REPLICAS_COLOCATED",
            `Multiple control plane replicas share machine "${machine}".`,
            n.id,
          ),
        );
  }
  for (const [machine, list] of groupBy("agent")) {
    if (list.length > 1)
      for (const n of list)
        p.push(
          (many ? err : warn)(
            "AGENTS_COLOCATED",
            `Multiple agents share machine "${machine}".`,
            n.id,
          ),
        );
  }

  if (bp.transport === "mtls") {
    if (!bp.tlsMaterialDir)
      p.push(err("MTLS_NO_MATERIAL_DIR", "mTLS transport requires a TLS material directory."));
    for (const m of machines) {
      const host = (m.data as MachineData).host;
      if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host))
        p.push(
          err(
            "MTLS_IP_LITERAL_HOST",
            `mTLS requires a hostname; machine "${(m.data as MachineData).name}" uses IP literal ${host}.`,
            m.id,
          ),
        );
    }
  }

  const stores = nodesOf(bp, "store");
  if (stores.length === 1)
    p.push(warn("SINGLE_STORE", "Single store replica: no fault tolerance.", stores[0].id));
  if (stores.length > 1 && stores.length % 2 === 0)
    p.push(warn("STORE_EVEN_REPLICAS", `Store has an even replica count (${stores.length}).`));
  const cps = nodesOf(bp, "controlPlane");
  if (cps.length === 1)
    p.push(warn("SINGLE_CONTROL_PLANE", "Single control plane replica.", cps[0].id));
  if (nodesOf(bp, "agent").length === 0)
    p.push(warn("NO_AGENTS", "No node agents: workloads cannot be scheduled."));

  return p;
}

const CONCURRENCY = ["Allow", "Forbid", "Replace"];

function instanceCount(node: BlueprintNode, agentCount: number): number {
  const d = node.data as WorkloadData;
  switch (node.kind) {
    case "deployment":
      return (d.replicas ?? 0) + (d.disruption?.maxSurge ?? 0);
    case "statefulSet":
      return d.replicas ?? 0;
    case "job":
      return 1;
    case "daemonSet":
      return agentCount;
    default:
      return 0;
  }
}

function validateApplication(bp: Blueprint): Problem[] {
  const p: Problem[] = [];
  const tenants = nodesOf(bp, "tenant");
  const tenantIds = tenants.map((t) => (t.data as TenantData).id);
  const agents = nodesOf(bp, "agent");
  const agentLabels = new Set(agents.flatMap((a) => (a.data as AgentData).labels ?? []));
  const workloads = workloadNodes(bp);

  const seenNames = new Map<string, number>();
  for (const w of workloads) {
    const d = w.data as WorkloadData;
    if (!d.name?.trim()) p.push(err("WORKLOAD_NAME_BLANK", "Workload name is blank.", w.id));
    else seenNames.set(d.name, (seenNames.get(d.name) ?? 0) + 1);
    if (!d.module?.name?.trim() || !d.module?.version?.trim())
      p.push(err("MODULE_COORDINATE_BLANK", "Module name and version are required.", w.id));
  }
  for (const w of workloads) {
    const d = w.data as WorkloadData;
    if (d.name && (seenNames.get(d.name) ?? 0) > 1)
      p.push(err("WORKLOAD_NAME_DUPLICATE", `Workload name "${d.name}" is used twice.`, w.id));
  }

  const tenantScoped = bp.nodes.filter((n) =>
    [
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
    ].includes(n.kind),
  );
  for (const n of tenantScoped) {
    const tid = tenantIdOf(bp, n);
    if (!tid || !tenantIds.includes(tid))
      p.push(err("TENANT_UNKNOWN", "Resource does not belong to a known tenant.", n.id));
  }

  for (const s of nodesOf(bp, "service")) {
    const d = s.data as ServiceData;
    const targetsFromEdges = bp.edges
      .filter((e) => e.kind === "fronts" && e.source === s.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n));
    const targetsFromNames = (d.deploymentNames ?? [])
      .map((name) =>
        workloads.find(
          (w) =>
            (w.data as WorkloadData).name === name &&
            (w.kind === "deployment" || w.kind === "statefulSet"),
        ),
      )
      .filter((n): n is BlueprintNode => Boolean(n));
    const targets = [...targetsFromEdges, ...targetsFromNames];
    if (targets.length === 0)
      p.push(
        err(
          "SERVICE_TARGET_MISSING",
          "Service fronts no existing deployment or statefulSet.",
          s.id,
        ),
      );
    if (d.port < 1 || d.port > 65535 || d.targetPort < 1 || d.targetPort > 65535)
      p.push(err("SERVICE_PORT_RANGE", "Service ports must be between 1 and 65535.", s.id));
    const serviceTenant = tenantIdOf(bp, s);
    for (const t of targets) {
      const tt = tenantIdOf(bp, t);
      if (serviceTenant && tt && serviceTenant !== tt)
        p.push(
          err(
            "SERVICE_CROSS_TENANT",
            `Service "${d.name}" and its target live in different tenants.`,
            s.id,
          ),
        );
    }
  }

  for (const np of nodesOf(bp, "networkPolicy")) {
    const d = np.data as NetworkPolicyData;
    const edgeCallers = bp.edges
      .filter((e) => e.kind === "allowsCaller" && e.source === np.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as TenantData).id);
    const callers = [...(d.allowedCallerTenantIds ?? []), ...edgeCallers];
    for (const c of callers)
      if (!tenantIds.includes(c))
        p.push(
          err(
            "POLICY_ALLOWED_TENANT_UNKNOWN",
            `Allowed caller tenant "${c}" does not exist.`,
            np.id,
          ),
        );
    const restricts = bp.edges.filter((e) => e.kind === "restricts" && e.source === np.id);
    if ((d.deploymentNames ?? []).length === 0 && restricts.length === 0 && callers.length === 0)
      p.push(
        err("POLICY_NO_DIRECTION", "Policy names neither deployments nor allowed callers.", np.id),
      );
  }

  for (const w of workloads) {
    const d = w.data as WorkloadData;
    if (typeof d.replicas === "number" && d.replicas < 0)
      p.push(err("REPLICAS_NEGATIVE", "Replicas cannot be negative.", w.id));
    if (d.autoscale) {
      const a = d.autoscale;
      if (a.maxReplicas < a.minReplicas || a.targetCpuUtilizationPercent <= 0)
        p.push(err("AUTOSCALE_RANGE", "Autoscale range or target CPU is invalid.", w.id));
    }
    if (
      d.disruption &&
      (d.disruption.maxUnavailable ?? 0) === 0 &&
      (d.disruption.maxSurge ?? 0) === 0
    )
      p.push(err("DISRUPTION_BOTH_ZERO", "maxUnavailable and maxSurge cannot both be zero.", w.id));
    if (w.kind === "daemonSet") {
      if (d.placement?.antiAffinity)
        p.push(err("DAEMONSET_ANTI_AFFINITY", "DaemonSets cannot declare anti-affinity.", w.id));
      if (d.disruption?.maxSurge !== undefined)
        p.push(err("DAEMONSET_MAX_SURGE", "DaemonSets do not support maxSurge.", w.id));
    }
    if (w.kind === "cronJob") {
      const fields = (d.schedule ?? "").trim().split(/\s+/).filter(Boolean);
      if (fields.length !== 5)
        p.push(err("CRON_SCHEDULE_INVALID", "Cron schedule must have 5 fields.", w.id));
      if (!CONCURRENCY.includes(d.concurrencyPolicy ?? ""))
        p.push(
          err("CRON_POLICY_INVALID", "Concurrency policy must be Allow, Forbid or Replace.", w.id),
        );
    }
    const reqMem = parseMemory(d.resources?.request.memory);
    const limMem = parseMemory(d.resources?.limit.memory);
    const reqCpu = parseCpu(d.resources?.request.cpu);
    const limCpu = parseCpu(d.resources?.limit.cpu);
    if (reqMem > limMem || reqCpu > limCpu)
      p.push(err("RESOURCES_REQUEST_OVER_LIMIT", "Resource request exceeds its limit.", w.id));
    if (d.artifact?.source === "jar" && d.artifact.path && !d.artifact.path.startsWith("/"))
      p.push(warn("JAR_PATH_RELATIVE", "Jar artifact path is not absolute.", w.id));
    if (d.placement?.requiredLabels?.length) {
      for (const label of d.placement.requiredLabels)
        if (!agentLabels.has(label))
          p.push(
            warn("REQUIRED_LABEL_UNMATCHED", `No agent carries required label "${label}".`, w.id),
          );
    }
    if (d.placement?.antiAffinity && (d.replicas ?? 0) > agents.length)
      p.push(
        warn(
          "ANTI_AFFINITY_SHORT",
          `Anti-affinity with ${d.replicas} replicas but only ${agents.length} agents.`,
          w.id,
        ),
      );
    if (d.artifact?.source === "registry" && nodesOf(bp, "andvari").length === 0)
      p.push(
        warn("NO_ANDVARI_FOR_REGISTRY", "Registry-sourced workload but no andvari role.", w.id),
      );
  }

  for (const lr of nodesOf(bp, "limitRange")) {
    const d = lr.data as LimitRangeData;
    const tid = tenantIdOf(bp, lr);
    for (const w of workloads) {
      if (tenantIdOf(bp, w) !== tid) continue;
      const wd = w.data as WorkloadData;
      const mem = parseMemory(wd.resources?.request.memory);
      const cpu = parseCpu(wd.resources?.request.cpu);
      if (
        mem < parseMemory(d.min.memory) ||
        mem > parseMemory(d.max.memory) ||
        cpu < parseCpu(d.min.cpu) ||
        cpu > parseCpu(d.max.cpu)
      )
        p.push(
          err(
            "LIMITRANGE_VIOLATION",
            `Request is outside the tenant limit range (${d.min.memory}/${d.min.cpu} – ${d.max.memory}/${d.max.cpu}).`,
            w.id,
          ),
        );
    }
  }

  for (const t of tenants) {
    const td = t.data as TenantData;
    let mem = 0;
    let cpu = 0;
    let instances = 0;
    const owned = workloads.filter((w) => tenantIdOf(bp, w) === td.id);
    for (const w of owned) {
      const wd = w.data as WorkloadData;
      const count = instanceCount(w, agents.length);
      mem += parseMemory(wd.resources?.request.memory) * count;
      cpu += parseCpu(wd.resources?.request.cpu) * count;
      instances += count;
    }
    if (
      mem > td.quota.maxMemoryBytes ||
      cpu > td.quota.maxCpuMillicores ||
      instances > td.quota.maxInstances
    )
      p.push(
        err(
          "QUOTA_EXCEEDED",
          `Tenant "${td.id}" quota exceeded (mem ${Math.round(mem / 1024 / 1024)}Mi, cpu ${cpu}m, instances ${instances}).`,
          t.id,
        ),
      );
  }

  for (const s of nodesOf(bp, "secret")) {
    const d = s.data as SecretData;
    p.push(
      info("SECRET_NO_VALUE_AT_RUN", `Secret "${d.key}" gets its value only at run time.`, s.id),
    );
  }

  for (const c of nodesOf(bp, "configEntry")) {
    const d = c.data as ConfigEntryData;
    if (!d.key?.trim()) p.push(err("WORKLOAD_NAME_BLANK", "Config entry key is blank.", c.id));
  }

  for (const st of nodesOf(bp, "store")) {
    const d = st.data as StoreData;
    if (!d.raftPort || !d.clientPort)
      p.push(err("PORT_CONFLICT", "Store ports must be set.", st.id));
  }
  for (const r of bp.nodes.filter((n) =>
    ["controlPlane", "fafnir", "muninn", "andvari"].includes(n.kind),
  )) {
    const d = r.data as RoleData;
    if (!d.port) p.push(err("PORT_CONFLICT", `${r.kind} port must be set.`, r.id));
  }

  return p;
}

export function validate(bp: Blueprint): Problem[] {
  return [...validateTopology(bp), ...validateApplication(bp)];
}
