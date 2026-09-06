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
import { machineNameOf, tenantIdOf } from "./effective";
import { portConflicts } from "./ports";
import { isValidCpu, isValidMemory, parseCpu, parseMemory } from "./units";

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
      if (!machine) p.push(err("UNKNOWN_MACHINE", `${n.kind} is not placed on any machine.`, n.id));
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

  // topology.yaml carries one fafnir keyFile for the whole role, so a second replica naming a
  // different file has that value dropped -- and it is the field deciding whether that replica can
  // decrypt anything.
  const keyFiles = new Set(
    nodesOf(bp, "fafnir")
      .map((n) => (n.data as RoleData).keyFile?.trim())
      .filter((f): f is string => Boolean(f)),
  );
  if (keyFiles.size > 1)
    for (const n of nodesOf(bp, "fafnir").slice(1))
      p.push(
        warn(
          "FAFNIR_KEYFILE_PER_ROLE",
          `The key file is one setting for the whole fafnir role, not per replica -- every replica will use "${[...keyFiles][0]}".`,
          n.id,
        ),
      );

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
      const m = machineNameOf(bp, n) ?? "";
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
  // Every replicated role, not just the two most obvious: a second Fafnir, Muninn or Andvari on
  // one machine is the same single point of failure, and the server-side pass already said so.
  colocated("fafnir", "REPLICAS_COLOCATED", "fafnir replicas");
  colocated("muninn", "REPLICAS_COLOCATED", "muninn replicas");
  colocated("andvari", "REPLICAS_COLOCATED", "andvari replicas");
  colocated("agent", "AGENTS_COLOCATED", "agents");

  if (bp.transport === "mtls") {
    if (!bp.tlsMaterialDir)
      p.push(err("MTLS_NO_MATERIAL_DIR", "mTLS transport requires a TLS material directory."));
    for (const m of machines) {
      const host = (m.data as MachineData).host;
      // Both families: the PKI mints DNS-only subject alternative names, so any IP literal fails
      // hostname verification. A hostname never contains a colon, so that alone identifies IPv6.
      if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(":"))
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
  const declaredTenantIds = tenants.map((t) => (t.data as TenantData).id);
  const agents = nodesOf(bp, "agent");
  const agentLabels = new Set(agents.flatMap((a) => (a.data as AgentData).labels ?? []));
  const workloads = workloadNodes(bp);

  const seenNames = new Map<string, number>();
  for (const w of workloads) {
    const d = w.data as WorkloadData;
    if (!d.name?.trim()) p.push(err("WORKLOAD_NAME_BLANK", "Workload name is blank.", w.id));
    else {
      const key = `${tenantIdOf(bp, w) ?? ""}/${d.name}`;
      seenNames.set(key, (seenNames.get(key) ?? 0) + 1);
    }
    // Named separately: one message for two different blanks left the user guessing which.
    if (!d.module?.name?.trim())
      p.push(err("MODULE_COORDINATE_BLANK", "Module name is required.", w.id));
    if (!d.module?.version?.trim())
      p.push(err("MODULE_COORDINATE_BLANK", "Module version is required.", w.id));
  }
  // Scoped by tenant, because the platform keys a workload by (tenant, name): the same app
  // deployed for two tenants is the ordinary case, not a collision.
  for (const w of workloads) {
    const d = w.data as WorkloadData;
    const key = `${tenantIdOf(bp, w) ?? ""}/${d.name}`;
    if (d.name && (seenNames.get(key) ?? 0) > 1)
      p.push(
        err(
          "WORKLOAD_NAME_DUPLICATE",
          `Workload name "${d.name}" is used twice in tenant "${tenantIdOf(bp, w) ?? "default"}".`,
          w.id,
        ),
      );
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
    if (!tid || !declaredTenantIds.includes(tid))
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
            (w.kind === "deployment" || w.kind === "statefulSet" || w.kind === "daemonSet"),
        ),
      )
      .filter((n): n is BlueprintNode => Boolean(n));
    const targets = [...targetsFromEdges, ...targetsFromNames];
    if (targets.length === 0)
      p.push(
        err(
          "SERVICE_TARGET_MISSING",
          "Service fronts no existing deployment, statefulSet or daemonSet.",
          s.id,
        ),
      );
    if (
      d.port < 1 ||
      d.port > 65535 ||
      (d.targetPort !== undefined && (d.targetPort < 1 || d.targetPort > 65535))
    )
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

  // Mirrors the control plane's own ServiceAdvisories#overlapWarnings exactly: two Services in
  // the same tenant fronting even one of the same deployment names are announced, never blocked
  // -- a deliberate shared front door (a stable name beside a versioned one) looks identical to a
  // copy-pasted deploymentNames mistake, and nothing else here tells them apart. Matched on
  // declared deployment names only, the same way the real ServiceSpec is -- not on port, which the
  // platform's own check never looks at either.
  const serviceOverlapInputs = nodesOf(bp, "service").map((s) => {
    const d = s.data as ServiceData;
    const edgeNames = bp.edges
      .filter((e) => e.kind === "fronts" && e.source === s.id)
      .map((e) => bp.nodes.find((n) => n.id === e.target))
      .filter((n): n is BlueprintNode => Boolean(n))
      .map((n) => (n.data as WorkloadData).name);
    return {
      node: s,
      name: d.name,
      tenantId: tenantIdOf(bp, s),
      deploymentNames: new Set([...(d.deploymentNames ?? []), ...edgeNames]),
    };
  });
  for (let i = 0; i < serviceOverlapInputs.length; i++) {
    for (let j = i + 1; j < serviceOverlapInputs.length; j++) {
      const a = serviceOverlapInputs[i];
      const b = serviceOverlapInputs[j];
      if (!a.tenantId || a.tenantId !== b.tenantId) continue;
      const shared = [...a.deploymentNames].filter((n) => b.deploymentNames.has(n)).sort();
      if (shared.length === 0) continue;
      p.push(
        warn(
          "SERVICE_OVERLAP",
          `Service "${b.name}" fronts deployment(s) ${shared.join(", ")} already fronted by service "${a.name}" in the same tenant -- both names route to the same instances.`,
          b.node.id,
        ),
      );
      p.push(
        warn(
          "SERVICE_OVERLAP",
          `Service "${a.name}" fronts deployment(s) ${shared.join(", ")} already fronted by service "${b.name}" in the same tenant -- both names route to the same instances.`,
          a.node.id,
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
      if (!declaredTenantIds.includes(c))
        p.push(
          err(
            "POLICY_ALLOWED_TENANT_UNKNOWN",
            `Allowed caller tenant "${c}" does not exist.`,
            np.id,
          ),
        );
    const restricts = bp.edges.filter((e) => e.kind === "restricts" && e.source === np.id);
    const policyTenant = tenantIdOf(bp, np);
    for (const edge of restricts) {
      const target = bp.nodes.find((n) => n.id === edge.target);
      if (target && tenantIdOf(bp, target) !== policyTenant)
        p.push(
          err(
            "POLICY_CROSS_TENANT",
            `Policy "${d.name}" restricts a workload in a different tenant.`,
            np.id,
          ),
        );
    }
    // A NetworkPolicy node always restricts ingress -- an empty allowed-caller list is the
    // deny-every-caller policy, which the renderer emits as an explicit empty list -- so the only
    // thing left to check is that the policy scopes something.
    if ((d.deploymentNames ?? []).length === 0 && restricts.length === 0)
      p.push(
        info(
          "POLICY_TENANT_WIDE",
          "Policy names no deployment, so it applies to every workload in its tenant.",
          np.id,
        ),
      );
  }

  for (const w of workloads) {
    const d = w.data as WorkloadData;
    if (typeof d.replicas === "number" && d.replicas < 0)
      p.push(err("REPLICAS_NEGATIVE", "Replicas cannot be negative.", w.id));
    if (typeof d.replicas === "number" && !Number.isInteger(d.replicas))
      p.push(
        err("REPLICAS_FRACTIONAL", `Replicas must be a whole number, not ${d.replicas}.`, w.id),
      );
    if (d.autoscale) {
      const a = d.autoscale;
      // Mirrors AutoscalePolicy's own compact constructor: minReplicas has no relationship to
      // check against another field for this one, so it needs its own standalone bound the same
      // way REPLICAS_NEGATIVE does for the base replica count above.
      if (a.minReplicas < 0)
        p.push(err("AUTOSCALE_RANGE", `minReplicas must not be negative: ${a.minReplicas}.`, w.id));
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
    if (d.disruption) {
      // Mirrors DisruptionBudget's own compact constructor: each bound's own non-negativity is
      // independent of whether they're both zero (DISRUPTION_BOTH_ZERO, below), so it needs its
      // own check the same way AUTOSCALE_RANGE's minReplicas check does above.
      if ((d.disruption.maxUnavailable ?? 0) < 0)
        p.push(
          err(
            "DISRUPTION_RANGE",
            `maxUnavailable must not be negative: ${d.disruption.maxUnavailable}.`,
            w.id,
          ),
        );
      if (d.disruption.maxSurge !== undefined && d.disruption.maxSurge < 0)
        p.push(
          err("DISRUPTION_RANGE", `maxSurge must not be negative: ${d.disruption.maxSurge}.`, w.id),
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
    for (const [field, value, valid] of [
      ["request memory", d.resources?.request.memory, isValidMemory],
      ["request cpu", d.resources?.request.cpu, isValidCpu],
      ["limit memory", d.resources?.limit.memory, isValidMemory],
      ["limit cpu", d.resources?.limit.cpu, isValidCpu],
    ] as const) {
      // An unparseable quantity silently became NaN, which compares false against every bound --
      // so a one-character typo printed "NaN" at the operator and switched the quota and
      // limit-range rules off without a word.
      if (value?.trim() && !valid(value))
        p.push(
          err(
            "INVALID_QUANTITY",
            `${field} "${value}" is not a valid quantity -- use e.g. 64Mi, 1Gi, 500m, 2.`,
            w.id,
          ),
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
    for (const [field, value, valid] of [
      ["min memory", d.min?.memory, isValidMemory],
      ["min cpu", d.min?.cpu, isValidCpu],
      ["max memory", d.max?.memory, isValidMemory],
      ["max cpu", d.max?.cpu, isValidCpu],
    ] as const) {
      if (value?.trim() && !valid(value))
        p.push(
          err(
            "INVALID_QUANTITY",
            `Limit range ${field} "${value}" is not a valid quantity -- use e.g. 64Mi, 500m.`,
            lr.id,
          ),
        );
    }
    // The renderer requires both halves of a bound to emit it at all (the platform reads a
    // present bound as complete and refuses one carrying a blank) -- so a memory value typed in
    // with no matching cpu, or vice versa, is silently dropped at export time with nothing here
    // ever having said so. LIMITRANGE_NO_BOUNDS below only fires once every bound is fully empty,
    // which missed exactly this half-filled case.
    for (const [label, memory, cpu] of [
      ["min", d.min?.memory, d.min?.cpu],
      ["max", d.max?.memory, d.max?.cpu],
    ] as const) {
      const memoryFilled = Boolean(memory?.trim());
      const cpuFilled = Boolean(cpu?.trim());
      if (memoryFilled !== cpuFilled)
        p.push(
          warn(
            "LIMITRANGE_HALF_FILLED",
            `Limit range ${label} only has ${memoryFilled ? "memory" : "cpu"} filled in -- both are required together, so this ${label} bound is dropped entirely when exported.`,
            lr.id,
          ),
        );
    }
    // Checked on the limit range itself, before any workload is measured against it: an inverted
    // range no request can ever satisfy was reported as a violation by each deployment, sending
    // the operator to fix a value that was never the problem.
    if (bounds.minMem !== undefined && bounds.maxMem !== undefined && bounds.minMem > bounds.maxMem)
      p.push(
        err(
          "LIMITRANGE_INVERTED",
          `Minimum memory (${d.min.memory}) exceeds the maximum (${d.max.memory}); no request can satisfy this range.`,
          lr.id,
        ),
      );
    if (bounds.minCpu !== undefined && bounds.maxCpu !== undefined && bounds.minCpu > bounds.maxCpu)
      p.push(
        err(
          "LIMITRANGE_INVERTED",
          `Minimum cpu (${d.min.cpu}) exceeds the maximum (${d.max.cpu}); no request can satisfy this range.`,
          lr.id,
        ),
      );
    if (Object.values(bounds).every((b) => b === undefined)) {
      p.push(
        info(
          "LIMITRANGE_NO_BOUNDS",
          "Limit range declares no bounds and constrains nothing.",
          lr.id,
        ),
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
  const tenantIds = new Map<string, string[]>();
  for (const t of tenants) {
    const id = (t.data as TenantData).id?.trim() ?? "";
    if (!id) {
      p.push(err("TENANT_ID_BLANK", "Tenant id is blank.", t.id));
      continue;
    }
    tenantIds.set(id, [...(tenantIds.get(id) ?? []), t.id]);
  }
  // Two tenant nodes carrying one id is a duplicate, not multi-tenancy: counting nodes rather
  // than ids told the operator to switch to mTLS, which silenced the message and left the real
  // fault -- one tenant declared twice, the second silently overwriting the first -- in place.
  for (const [id, nodeIds] of tenantIds)
    if (nodeIds.length > 1)
      for (const nodeId of nodeIds)
        p.push(
          err("TENANT_DUPLICATE", `Tenant id "${id}" is declared ${nodeIds.length} times.`, nodeId),
        );

  if (bp.transport !== "mtls" && tenantIds.size > 1)
    for (const [, nodeIds] of [...tenantIds].slice(1))
      p.push(
        err(
          "PLAINTEXT_MULTI_TENANT",
          `Plaintext transport permits only one tenant; this design declares ${tenantIds.size}. Switch the topology to mTLS for real multi-tenancy.`,
          nodeIds[0],
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
      info(
        "SECRET_NO_VALUE_AT_RUN",
        `Secret "${d.key}" gets its value only at run time — type it in the Runner screen before pressing Run.`,
        s.id,
      ),
    );
  }

  // Every tenant-scoped resource the platform keys by (tenant, name): a second one silently
  // overwrote the first at apply time, with nothing said anywhere.
  const duplicatesOf = (
    kind: string,
    code: string,
    label: string,
    nameOf: (node: BlueprintNode) => string | undefined,
  ) => {
    const seen = new Map<string, BlueprintNode[]>();
    for (const n of nodesOf(bp, kind)) {
      const name = nameOf(n)?.trim();
      if (!name) continue;
      const key = `${tenantIdOf(bp, n) ?? ""}/${name}`;
      seen.set(key, [...(seen.get(key) ?? []), n]);
    }
    for (const [, group] of seen)
      if (group.length > 1)
        for (const n of group)
          p.push(err(code, `${label} "${nameOf(n)}" is declared twice in the same tenant.`, n.id));
  };
  duplicatesOf("service", "SERVICE_DUPLICATE", "Service", (n) => (n.data as ServiceData).name);
  duplicatesOf(
    "networkPolicy",
    "POLICY_DUPLICATE",
    "Network policy",
    (n) => (n.data as NetworkPolicyData).name,
  );
  duplicatesOf(
    "configEntry",
    "CONFIG_DUPLICATE",
    "Config key",
    (n) => (n.data as ConfigEntryData).key,
  );
  duplicatesOf("secret", "SECRET_DUPLICATE", "Secret key", (n) => (n.data as SecretData).key);
  duplicatesOf("limitRange", "LIMITRANGE_DUPLICATE", "Limit range for tenant", (n) =>
    tenantIdOf(bp, n),
  );

  for (const c of nodesOf(bp, "configEntry")) {
    const d = c.data as ConfigEntryData;
    if (!d.key?.trim()) p.push(err("CONFIG_KEY_BLANK", "Config entry key is blank.", c.id));
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
