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

/**
 * The tenant a node belongs to. An edge wins over the node's own tenantId field: the edge is a
 * live link to the tenant node, so it survives that tenant being renamed, while the text field is
 * a copy taken when it was typed. Without that precedence, renaming a tenant left every node
 * still drawn as belonging to it rendering the old id -- and inconsistently, since a node with no
 * text field of its own did follow the rename.
 */
export function tenantIdOf(bp: Blueprint, node: BlueprintNode): string | undefined {
  const edge = bp.edges.find((e) => e.kind === "belongsTo" && e.source === node.id);
  const tenant = edge ? bp.nodes.find((n) => n.id === edge.target) : undefined;
  if (tenant) return (tenant.data as TenantData).id;
  return (node.data as { tenantId?: string }).tenantId || undefined;
}

/** The machine a platform role is placed on, with the same edge-wins-over-text-field rule. */
export function machineNameOf(bp: Blueprint, node: BlueprintNode): string | undefined {
  const edge = bp.edges.find((e) => e.kind === "placedOn" && e.source === node.id);
  const machine = edge ? bp.nodes.find((n) => n.id === edge.target) : undefined;
  if (machine) return (machine.data as MachineData).name;
  return (node.data as { machine?: string }).machine || undefined;
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
      const machine = machineNameOf(bp, n) ?? "";
      if (!machine)
        p.push(err("UNKNOWN_MACHINE", `${n.kind} is not placed on any machine.`, n.id));
      else if (!names.includes(machine))
        p.push(
          err("UNKNOWN_MACHINE", `${n.kind} is placed on unknown machine "${machine}".`, n.id),
        );
    }
  }

  // Two agents sharing a node id is a hard error the platform itself refuses, and it is a pure
  // blueprint-level fact -- exactly what the live tier is for, rather than making the user press
  // Validate to hear about it.
  const seenNodeId = new Set<string>();
  for (const a of nodesOf(bp, "agent")) {
    const nodeId = (a.data as AgentData).nodeId;
    if (!nodeId?.trim()) {
      p.push(err("AGENT_NODE_ID_BLANK", "Agent has no node id.", a.id));
      continue;
    }
    if (seenNodeId.has(nodeId))
      p.push(err("DUPLICATE_NODE_ID", `Two agents share the node id "${nodeId}".`, a.id));
    seenNodeId.add(nodeId);
  }

  // topology.yaml carries one jvm flag list per role, not per replica, so two replicas of a role
  // with different flags cannot both be expressed -- the renderer unions them rather than dropping
  // either, and this says so before the user wonders why the file disagrees with the canvas.
  const flagsByKind = new Map<string, Set<string>>();
  for (const n of bp.nodes) {
    const flags = (n.data as { jvmFlags?: string[] }).jvmFlags;
    if (!flags?.length) continue;
    const seen = flagsByKind.get(n.kind);
    if (!seen) {
      flagsByKind.set(n.kind, new Set(flags));
      continue;
    }
    if (flags.some((f) => !seen.has(f)))
      p.push(
        warn(
          "JVM_FLAGS_PER_ROLE",
          `JVM flags are one list per role, not per replica -- every ${n.kind} replica will run the union of them.`,
          n.id,
        ),
      );
    for (const f of flags) seen.add(f);
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

  // One finding per colocated group, not one per member: every copy carried identical text and
  // pointed at the same role, so the extras only inflated the problem count.
  const colocated = (kind: string, code: string, what: string) => {
    for (const [machine, list] of groupBy(kind)) {
      if (list.length > 1)
        p.push(
          (many ? err : warn)(
            code,
            `${list.length} ${what} share machine "${machine}".`,
            list[0].id,
          ),
        );
    }
  };
  colocated("store", "REPLICAS_COLOCATED", "store replicas");
  colocated("controlPlane", "REPLICAS_COLOCATED", "control plane replicas");
  colocated("agent", "AGENTS_COLOCATED", "agents");

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
    if (typeof d.replicas === "number" && !Number.isInteger(d.replicas))
      p.push(err("REPLICAS_FRACTIONAL", `Replicas must be a whole number, not ${d.replicas}.`, w.id));
    if (d.autoscale) {
      const a = d.autoscale;
      if (a.maxReplicas < a.minReplicas)
        p.push(
          err(
            "AUTOSCALE_RANGE",
            `maxReplicas (${a.maxReplicas}) must be >= minReplicas (${a.minReplicas}).`,
            w.id,
          ),
        );
      if (a.targetCpuUtilizationPercent <= 0)
        p.push(
          err(
            "AUTOSCALE_RANGE",
            `targetCpuUtilizationPercent (${a.targetCpuUtilizationPercent}) must be greater than 0.`,
            w.id,
          ),
        );
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
          `Anti-affinity with ${d.replicas} replicas but only ${agents.length} ${agents.length === 1 ? "agent" : "agents"}.`,
          w.id,
        ),
      );
    if (nodesOf(bp, "andvari").length === 0) {
      if (d.artifact?.source === "registry")
        p.push(
          err(
            "NO_ANDVARI_FOR_REGISTRY",
            "Registry-sourced workload but no andvari role: the coordinate can never resolve.",
            w.id,
          ),
        );
      if (d.artifact?.source === "jar")
        p.push(
          err(
            "NO_ANDVARI_FOR_JAR",
            "Jar-sourced workload but no andvari role: the run has nowhere to push the jar.",
            w.id,
          ),
        );
    }
  }

  for (const lr of nodesOf(bp, "limitRange")) {
    const d = lr.data as LimitRangeData;
    const tid = tenantIdOf(bp, lr);
    // Each bound is independently optional in the platform, so a limit range with none filled in
    // constrains nothing -- checking against it reported every deployment in the tenant as outside
    // a range rendered as "/ – /", which is the state a freshly-dropped node is in.
    const bounds = {
      minMem: d.min?.memory?.trim() ? parseMemory(d.min.memory) : undefined,
      maxMem: d.max?.memory?.trim() ? parseMemory(d.max.memory) : undefined,
      minCpu: d.min?.cpu?.trim() ? parseCpu(d.min.cpu) : undefined,
      maxCpu: d.max?.cpu?.trim() ? parseCpu(d.max.cpu) : undefined,
    };
    if (Object.values(bounds).every((b) => b === undefined)) {
      p.push(
        info("LIMITRANGE_NO_BOUNDS", "Limit range declares no bounds and constrains nothing.", lr.id),
      );
      continue;
    }
    const shown = (memory?: string, cpu?: string) => `${memory || "*"}/${cpu || "*"}`;
    for (const w of workloads) {
      if (tenantIdOf(bp, w) !== tid) continue;
      const wd = w.data as WorkloadData;
      const mem = parseMemory(wd.resources?.request.memory);
      const cpu = parseCpu(wd.resources?.request.cpu);
      if (
        (bounds.minMem !== undefined && mem < bounds.minMem) ||
        (bounds.maxMem !== undefined && mem > bounds.maxMem) ||
        (bounds.minCpu !== undefined && cpu < bounds.minCpu) ||
        (bounds.maxCpu !== undefined && cpu > bounds.maxCpu)
      )
        p.push(
          err(
            "LIMITRANGE_VIOLATION",
            `Request is outside the tenant limit range (${shown(d.min?.memory, d.min?.cpu)} – ${shown(d.max?.memory, d.max?.cpu)}).`,
            w.id,
          ),
        );
    }
  }

  // The control plane refuses a second operator tenant under plaintext -- it has no caller
  // identity to tell them apart -- so a design that can never deploy should say so while it is
  // being drawn, not only once Validate is pressed.
  if (bp.transport !== "mtls" && tenants.length > 1)
    for (const t of tenants.slice(1))
      p.push(
        err(
          "PLAINTEXT_MULTI_TENANT",
          `Plaintext transport permits only one tenant; this design declares ${tenants.length}. Switch the topology to mTLS for real multi-tenancy.`,
          t.id,
        ),
      );

  for (const t of tenants) {
    const td = t.data as TenantData;
    for (const [field, value] of [
      ["maxMemoryBytes", td.quota?.maxMemoryBytes],
      ["maxCpuMillicores", td.quota?.maxCpuMillicores],
      ["maxInstances", td.quota?.maxInstances],
    ] as const)
      if (typeof value === "number" && value <= 0)
        p.push(err("QUOTA_NOT_POSITIVE", `Tenant quota ${field} must be greater than 0.`, t.id));
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

  // A port that is unset, or outside what any process can bind, is its own fault -- reporting it
  // as PORT_CONFLICT said the opposite of what the message did, and merged every unset port into
  // one bogus "conflict on port undefined".
  const checkPort = (nodeId: string, what: string, port: number | undefined) => {
    if (port === undefined || port === null || Number.isNaN(port) || port === 0) {
      p.push(err("PORT_UNSET", `${what} must be set.`, nodeId));
      return;
    }
    if (!Number.isInteger(port) || port < 1 || port > 65535)
      p.push(err("PORT_RANGE", `${what} must be a whole number between 1 and 65535.`, nodeId));
  };
  for (const st of nodesOf(bp, "store")) {
    const d = st.data as StoreData;
    checkPort(st.id, "Store raft port", d.raftPort);
    checkPort(st.id, "Store client port", d.clientPort);
  }
  for (const r of bp.nodes.filter((n) =>
    ["controlPlane", "fafnir", "muninn", "andvari"].includes(n.kind),
  )) {
    checkPort(r.id, `${r.kind} port`, (r.data as RoleData).port);
  }
  for (const a of nodesOf(bp, "agent")) {
    checkPort(a.id, "Agent gossip port", (a.data as AgentData).gossipPort);
  }

  return p;
}

export function validate(bp: Blueprint): Problem[] {
  return [...validateTopology(bp), ...validateApplication(bp)];
}
