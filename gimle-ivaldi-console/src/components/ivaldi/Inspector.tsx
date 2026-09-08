import { Link2, Link2Off, PanelRightClose, PanelRightOpen, Settings2, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";

import {
  EDGE_LABELS,
  edgeKindFor,
  isPlacedRole,
  isTenantScoped,
  isWorkload,
  KIND_LABELS,
  type AgentData,
  type Blueprint,
  type BlueprintNode,
  type ConfigEntryData,
  type EdgeKind,
  type LimitRangeData,
  type MachineData,
  type NetworkPolicyData,
  type NodeData,
  type Problem,
  type RoleData,
  type SecretData,
  type ServiceData,
  type StoreData,
  type TenantData,
  type WorkloadData,
  restrictsIngress,
} from "@/lib/blueprint";
import { effectiveMachine, effectiveTenant } from "@/lib/effective";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useUiStore } from "@/stores/useUiStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { NodeErrorBoundary } from "./NodeErrorBoundary";
import {
  CheckboxField,
  CpuField,
  ListField,
  MemoryBytesField,
  MemoryField,
  MillicoresField,
  NumberField,
  ProblemList,
  SelectField,
  SuggestField,
  TextField,
} from "./fields";

/** Machine names and tenant ids currently on the canvas, for the pickers. */
function machineOptions(bp: Blueprint): string[] {
  return bp.nodes
    .filter((n) => n.kind === "machine")
    .map((n) => (n.data as MachineData).name)
    .filter(Boolean);
}

function tenantOptions(bp: Blueprint): string[] {
  return bp.nodes
    .filter((n) => n.kind === "tenant")
    .map((n) => (n.data as TenantData).id)
    .filter(Boolean);
}

function workloadOptions(bp: Blueprint): string[] {
  return bp.nodes
    .filter((n) => isWorkload(n.kind))
    .map((n) => (n.data as WorkloadData).name)
    .filter(Boolean);
}

/** Machine field: a placedOn link is authoritative, so the box turns read-only. */
function MachineField({
  blueprint,
  node,
  problems,
  onChange,
}: {
  blueprint: Blueprint;
  node: BlueprintNode;
  problems: Problem[];
  onChange: (machine: string) => void;
}) {
  const effective = effectiveMachine(blueprint, node);
  return (
    <SuggestField
      label="Machine"
      value={effective.value}
      options={machineOptions(blueprint)}
      readOnly={effective.fromEdge}
      onChange={onChange}
      hint={
        effective.fromEdge
          ? "Set by the link on the canvas. Remove the link below to type it here."
          : // Typing here does not draw a placedOn link -- it's a plain copy of the name, so it
            // won't survive the machine being renamed and won't show up in the Links section
            // below. Only dragging a connection between the two nodes on the canvas does that.
            "Free text, not a link. Drag a connection from this node to the machine on the canvas to link them."
      }
      problems={problems}
    />
  );
}

/** Tenant field: a belongsTo link is authoritative, so the box turns read-only. */
function TenantField({
  blueprint,
  node,
  problems,
  onChange,
  freeTextHint,
}: {
  blueprint: Blueprint;
  node: BlueprintNode;
  problems: Problem[];
  onChange: (tenantId: string) => void;
  /** Overrides the free-text-mode hint below. NetworkPolicy needs its own: dragging from it to a
   * Tenant on the canvas never sets this field (it adds an allowed caller instead -- see
   * edgeKindFor's own comment), so the generic "drag to link" instruction would be actively
   * misleading for this one kind. */
  freeTextHint?: string;
}) {
  const effective = effectiveTenant(blueprint, node);
  return (
    <SuggestField
      label="Tenant id"
      value={effective.value}
      options={tenantOptions(blueprint)}
      readOnly={effective.fromEdge}
      onChange={onChange}
      hint={
        effective.fromEdge
          ? "Set by the link on the canvas. Remove the link below to type it here."
          : // Same caveat as MachineField: this is a copy of the id, not a belongsTo link, so
            // renaming the tenant won't follow it and it won't appear in Links below.
            (freeTextHint ??
            "Free text, not a link. Drag a connection from this node to the tenant on the canvas to link them.")
      }
      problems={problems}
    />
  );
}

function nodeLabel(blueprint: Blueprint, id: string): string {
  const n = blueprint.nodes.find((x) => x.id === id);
  if (!n) return id;
  const d = n.data as unknown as Record<string, unknown>;
  const named = [d.name, d.id, d.nodeId, d.key].find(
    (v) => typeof v === "string" && v.trim() !== "",
  ) as string | undefined;
  return `${KIND_LABELS[n.kind]} ${named ?? ""}`.trim();
}

/** One other node this node could still legally link to, and which way the edge would run. */
interface LinkCandidate {
  source: string;
  target: string;
  kind: EdgeKind;
  otherId: string;
}

/**
 * Every other node this node doesn't already link to that a canvas drag could still connect it
 * to, in either direction -- the same {@link edgeKindFor} a drag's own onConnect checks, so a link
 * made here is indistinguishable from one dragged on the canvas. Checked both ways because which
 * end a link is legally drawn *from* depends on the edge kind (a Service fronts a Deployment, never
 * the other way around), and this node can be either end depending on what's selected.
 */
function linkCandidatesFor(blueprint: Blueprint, node: BlueprintNode): LinkCandidate[] {
  const existing = new Set(blueprint.edges.map((e) => `${e.kind}|${e.source}|${e.target}`));
  const candidates: LinkCandidate[] = [];
  for (const other of blueprint.nodes) {
    if (other.id === node.id) continue;
    const asSource = edgeKindFor(node.kind, other.kind);
    if (asSource && !existing.has(`${asSource}|${node.id}|${other.id}`))
      candidates.push({ source: node.id, target: other.id, kind: asSource, otherId: other.id });
    const asTarget = edgeKindFor(other.kind, node.kind);
    if (asTarget && !existing.has(`${asTarget}|${other.id}|${node.id}`))
      candidates.push({ source: other.id, target: node.id, kind: asTarget, otherId: other.id });
  }
  return candidates;
}

/** Keyboard/screen-reader path to create a link -- the only way to do it was a canvas drag. */
function AddLinkControl({ blueprint, node }: { blueprint: Blueprint; node: BlueprintNode }) {
  const connect = useBlueprintStore((s) => s.connect);
  const candidates = linkCandidatesFor(blueprint, node);
  const [choice, setChoice] = useState(0);
  useEffect(() => {
    if (choice >= candidates.length) setChoice(0);
  }, [candidates.length, choice]);
  if (candidates.length === 0) return null;
  const picked = candidates[Math.min(choice, candidates.length - 1)];
  return (
    <div className="mt-1.5 flex items-center gap-1.5">
      <select
        aria-label="Node to link to"
        className="h-7 min-w-0 flex-1 rounded-sm border border-border bg-background px-1.5 font-mono text-[10px] text-foreground outline-none focus:border-primary"
        value={choice}
        onChange={(e) => setChoice(Number(e.target.value))}
      >
        {candidates.map((c, i) => (
          <option key={`${c.kind}|${c.source}|${c.target}`} value={i}>
            {c.source === node.id ? EDGE_LABELS[c.kind] : `${EDGE_LABELS[c.kind]} (from)`}
            {" — "}
            {nodeLabel(blueprint, c.otherId)}
          </option>
        ))}
      </select>
      <button
        onClick={() => {
          const result = connect(picked.source, picked.target);
          if (!result.ok) toast.error("Link refused", { description: result.reason });
        }}
        className="inline-flex h-7 shrink-0 items-center gap-1 rounded-sm border border-primary bg-primary px-2 font-mono text-[10px] text-primary-foreground"
      >
        <Link2 className="size-3" /> Add link
      </button>
    </div>
  );
}

/** Every link the selected node takes part in, with a way to cut it -- and, below, a way to add
 *  one without ever touching the canvas (see AddLinkControl). */
function LinksSection({ blueprint, node }: { blueprint: Blueprint; node: BlueprintNode }) {
  const disconnect = useBlueprintStore((s) => s.disconnect);
  const select = useBlueprintStore((s) => s.select);
  const links = blueprint.edges.filter((e) => e.source === node.id || e.target === node.id);
  return (
    <div>
      <div className="hud-label">Links</div>
      {links.length === 0 ? (
        <p className="mt-1 text-[10px] text-muted-foreground">
          No links. Drag between two nodes on the canvas, or add one below.
        </p>
      ) : (
        <ul className="mt-1 space-y-1">
          {links.map((e) => {
            const otherId = e.source === node.id ? e.target : e.source;
            return (
              <li
                key={e.id}
                className="flex items-center justify-between gap-2 rounded-sm border border-border bg-card px-2 py-1"
              >
                <button
                  onClick={() => select(otherId)}
                  className="min-w-0 text-left font-mono text-[10px] text-foreground hover:text-primary"
                >
                  <span className="hud-label mr-1">{EDGE_LABELS[e.kind]}</span>
                  <span className="truncate">{nodeLabel(blueprint, otherId)}</span>
                </button>
                <button
                  title="Remove this link"
                  onClick={() => disconnect(e.id)}
                  className="shrink-0 rounded-sm border border-border p-1 text-muted-foreground hover:border-destructive hover:text-destructive"
                >
                  <Link2Off className="size-3" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <AddLinkControl blueprint={blueprint} node={node} />
    </div>
  );
}

function pick(problems: Problem[], codes: string[]): Problem[] {
  return problems.filter((p) => codes.includes(p.code));
}

function BlueprintSettings({ blueprint }: { blueprint: Blueprint }) {
  const patch = useBlueprintStore((s) => s.patchBlueprint);
  const problems = useValidationStore((s) => s.problems);
  return (
    <div className="space-y-3">
      <div className="hud-label">Blueprint</div>
      <TextField label="Name" value={blueprint.name} onChange={(name) => patch({ name })} />
      <TextField
        label="Version"
        value={blueprint.version}
        onChange={(version) => patch({ version })}
      />
      <SelectField
        label="Transport"
        value={blueprint.transport}
        options={["plaintext", "mtls"] as const}
        onChange={(transport) => patch({ transport })}
        problems={pick(problems, ["MTLS_NO_MATERIAL_DIR"])}
      />
      {blueprint.transport === "mtls" && (
        <TextField
          label="TLS material dir"
          value={blueprint.tlsMaterialDir ?? ""}
          onChange={(tlsMaterialDir) => patch({ tlsMaterialDir })}
        />
      )}
      <TextField
        label="Data root"
        value={blueprint.runtime.dataRoot}
        onChange={(dataRoot) => patch({ runtime: { ...blueprint.runtime, dataRoot } })}
      />
      <TextField
        label="Classpath"
        value={blueprint.runtime.classpath ?? ""}
        onChange={(classpath) =>
          patch({ runtime: { ...blueprint.runtime, classpath: classpath || undefined } })
        }
      />
    </div>
  );
}

function WorkloadForm({
  blueprint,
  node,
  problems,
  update,
}: {
  blueprint: Blueprint;
  node: BlueprintNode;
  problems: Problem[];
  update: (patch: Partial<NodeData>) => void;
}) {
  const d = node.data as WorkloadData;
  const kind = node.kind;
  return (
    <>
      <TextField
        label="Name"
        value={d.name}
        onChange={(name) => update({ name } as Partial<NodeData>)}
        problems={pick(problems, ["WORKLOAD_NAME_BLANK", "WORKLOAD_NAME_DUPLICATE"])}
      />
      <TenantField
        blueprint={blueprint}
        node={node}
        onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
        problems={pick(problems, ["TENANT_UNKNOWN", "QUOTA_EXCEEDED", "QUOTA_NOT_POSITIVE"])}
      />
      <TextField
        label="Module name"
        value={d.module.name}
        onChange={(name) => update({ module: { ...d.module, name } } as Partial<NodeData>)}
        problems={pick(problems, ["MODULE_COORDINATE_BLANK"])}
      />
      <TextField
        label="Module version"
        value={d.module.version}
        onChange={(version) => update({ module: { ...d.module, version } } as Partial<NodeData>)}
        problems={pick(problems, ["MODULE_VERSION_MISMATCH"])}
      />
      <SelectField
        label="Artifact source"
        value={d.artifact.source}
        options={["registry", "jar"] as const}
        onChange={(source) =>
          update({
            artifact:
              source === "jar"
                ? { source: "jar", path: "/abs/path/module.jar" }
                : { source: "registry" },
          } as Partial<NodeData>)
        }
        problems={pick(problems, ["NO_ANDVARI_FOR_REGISTRY"])}
      />
      {d.artifact.source === "jar" && (
        <TextField
          label="Jar path"
          value={d.artifact.path}
          onChange={(path) => update({ artifact: { source: "jar", path } } as Partial<NodeData>)}
          problems={pick(problems, ["JAR_PATH_BLANK", "JAR_PATH_RELATIVE"])}
        />
      )}
      {(kind === "deployment" || kind === "statefulSet") && (
        <NumberField
          label="Replicas"
          value={d.replicas}
          onChange={(replicas) => update({ replicas } as Partial<NodeData>)}
          problems={pick(problems, [
            "REPLICAS_NEGATIVE",
            "REPLICAS_FRACTIONAL",
            "ANTI_AFFINITY_SHORT",
          ])}
        />
      )}
      <CheckboxField
        label="Anti-affinity"
        checked={Boolean(d.placement?.antiAffinity)}
        onChange={(antiAffinity) =>
          update({ placement: { ...d.placement, antiAffinity } } as Partial<NodeData>)
        }
        // DaemonSets can't legally declare this (DAEMONSET_ANTI_AFFINITY, checked below) --
        // rendered anyway so a value of `true` set some other way (an import, an old manifest)
        // stays clearable, the same way DAEMONSET_MAX_SURGE is fixable via its own toggle.
        problems={kind === "daemonSet" ? pick(problems, ["DAEMONSET_ANTI_AFFINITY"]) : undefined}
      />
      {kind === "daemonSet" && (
        <CheckboxField
          label="Tolerate all taints"
          checked={Boolean(d.tolerateAllTaints)}
          onChange={(tolerateAllTaints) => update({ tolerateAllTaints } as Partial<NodeData>)}
        />
      )}
      <ListField
        label="Required labels"
        values={d.placement?.requiredLabels ?? []}
        onChange={(requiredLabels) =>
          update({ placement: { ...d.placement, requiredLabels } } as Partial<NodeData>)
        }
        problems={pick(problems, ["REQUIRED_LABEL_UNMATCHED"])}
      />
      {(kind === "deployment" || kind === "statefulSet") && (
        <>
          <CheckboxField
            label="Autoscale"
            checked={Boolean(d.autoscale)}
            onChange={(on) =>
              update({
                autoscale: on
                  ? { minReplicas: 1, maxReplicas: 3, targetCpuUtilizationPercent: 70 }
                  : undefined,
              } as Partial<NodeData>)
            }
          />
          {d.autoscale && (
            <div className="grid grid-cols-3 gap-2">
              <NumberField
                label="Min"
                value={d.autoscale.minReplicas}
                onChange={(minReplicas) =>
                  update({ autoscale: { ...d.autoscale!, minReplicas } } as Partial<NodeData>)
                }
                problems={pick(problems, ["AUTOSCALE_RANGE"])}
              />
              <NumberField
                label="Max"
                value={d.autoscale.maxReplicas}
                onChange={(maxReplicas) =>
                  update({ autoscale: { ...d.autoscale!, maxReplicas } } as Partial<NodeData>)
                }
              />
              <NumberField
                label="Target %"
                value={d.autoscale.targetCpuUtilizationPercent}
                onChange={(targetCpuUtilizationPercent) =>
                  update({
                    autoscale: { ...d.autoscale!, targetCpuUtilizationPercent },
                  } as Partial<NodeData>)
                }
                problems={pick(problems, ["AUTOSCALE_RANGE"])}
              />
            </div>
          )}
        </>
      )}
      {(kind === "deployment" || kind === "daemonSet" || kind === "statefulSet") && (
        <>
          <CheckboxField
            label="Disruption budget"
            checked={Boolean(d.disruption)}
            onChange={(on) =>
              update({
                disruption: on
                  ? { maxUnavailable: 1, maxSurge: kind === "deployment" ? 1 : undefined }
                  : undefined,
              } as Partial<NodeData>)
            }
          />
          {d.disruption && (
            <div className="grid grid-cols-2 gap-2">
              <NumberField
                label="Max unavailable"
                value={d.disruption.maxUnavailable}
                onChange={(maxUnavailable) =>
                  update({ disruption: { ...d.disruption!, maxUnavailable } } as Partial<NodeData>)
                }
                problems={pick(problems, [
                  "DISRUPTION_BOTH_ZERO",
                  "DISRUPTION_RANGE",
                  "DAEMONSET_MAX_SURGE",
                  "STATEFULSET_MAX_SURGE",
                ])}
              />
              {kind === "deployment" && (
                <NumberField
                  label="Max surge"
                  value={d.disruption.maxSurge}
                  onChange={(maxSurge) =>
                    update({ disruption: { ...d.disruption!, maxSurge } } as Partial<NodeData>)
                  }
                  problems={pick(problems, ["DISRUPTION_RANGE"])}
                />
              )}
            </div>
          )}
        </>
      )}
      {(kind === "job" || kind === "cronJob") && (
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Active deadline s"
            value={d.activeDeadlineSeconds}
            onChange={(activeDeadlineSeconds) =>
              update({ activeDeadlineSeconds } as Partial<NodeData>)
            }
          />
          <NumberField
            label="Backoff limit"
            value={d.backoffLimit}
            onChange={(backoffLimit) => update({ backoffLimit } as Partial<NodeData>)}
          />
        </div>
      )}
      {kind === "cronJob" && (
        <>
          <TextField
            label="Schedule"
            value={d.schedule ?? ""}
            onChange={(schedule) => update({ schedule } as Partial<NodeData>)}
            hint="5 fields: minute hour day-of-month month day-of-week"
            problems={pick(problems, ["CRON_SCHEDULE_INVALID"])}
          />
          <SelectField
            label="Concurrency policy"
            value={d.concurrencyPolicy ?? "Allow"}
            options={["Allow", "Forbid", "Replace"] as const}
            onChange={(concurrencyPolicy) => update({ concurrencyPolicy } as Partial<NodeData>)}
            problems={pick(problems, ["CRON_POLICY_INVALID"])}
          />
          <CheckboxField
            label="Suspend"
            checked={Boolean(d.suspend)}
            onChange={(suspend) => update({ suspend } as Partial<NodeData>)}
          />
        </>
      )}
      <div className="hud-label pt-1">Resources</div>
      <p className="-mt-1 text-[10px] text-muted-foreground">
        Used for tenant quota and limit-range checks here only. A module's real request and limit
        come from its own gimle-module.yaml inside the jar, so these values never reach the
        generated files.
      </p>
      <div className="grid grid-cols-2 gap-2">
        <MemoryField
          label="Request memory"
          value={d.resources.request.memory}
          onChange={(memory) =>
            update({
              resources: { ...d.resources, request: { ...d.resources.request, memory } },
            } as Partial<NodeData>)
          }
          problems={pick(problems, [
            "RESOURCES_REQUEST_OVER_LIMIT",
            "LIMITRANGE_VIOLATION",
            "QUOTA_EXCEEDED",
          ])}
        />
        <CpuField
          label="Request cpu"
          value={d.resources.request.cpu}
          onChange={(cpu) =>
            update({
              resources: { ...d.resources, request: { ...d.resources.request, cpu } },
            } as Partial<NodeData>)
          }
          problems={pick(problems, ["RESOURCES_REQUEST_OVER_LIMIT"])}
        />
        <MemoryField
          label="Limit memory"
          value={d.resources.limit.memory}
          onChange={(memory) =>
            update({
              resources: { ...d.resources, limit: { ...d.resources.limit, memory } },
            } as Partial<NodeData>)
          }
          problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
        />
        <CpuField
          label="Limit cpu"
          value={d.resources.limit.cpu}
          onChange={(cpu) =>
            update({
              resources: { ...d.resources, limit: { ...d.resources.limit, cpu } },
            } as Partial<NodeData>)
          }
        />
      </div>
    </>
  );
}

function NodeForm({
  blueprint,
  node,
  problems,
}: {
  blueprint: Blueprint;
  node: BlueprintNode;
  problems: Problem[];
}) {
  const updateNode = useBlueprintStore((s) => s.updateNode);
  const update = (patch: Partial<NodeData>) => updateNode(node.id, patch);

  switch (node.kind) {
    case "machine": {
      const d = node.data as MachineData;
      return (
        <>
          <TextField
            label="Name"
            value={d.name}
            onChange={(name) => update({ name } as Partial<NodeData>)}
            problems={pick(problems, ["DUPLICATE_MACHINE"])}
          />
          <TextField
            label="Host"
            value={d.host}
            onChange={(host) => update({ host } as Partial<NodeData>)}
            problems={pick(problems, ["MTLS_IP_LITERAL_HOST"])}
          />
        </>
      );
    }
    case "store": {
      const d = node.data as StoreData;
      return (
        <>
          <MachineField
            blueprint={blueprint}
            node={node}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "REPLICAS_COLOCATED"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <NumberField
              label="Raft port"
              value={d.raftPort}
              onChange={(raftPort) => update({ raftPort } as Partial<NodeData>)}
              problems={pick(problems, ["PORT_CONFLICT", "PORT_UNSET", "PORT_RANGE"])}
            />
            <NumberField
              label="Client port"
              value={d.clientPort}
              onChange={(clientPort) => update({ clientPort } as Partial<NodeData>)}
            />
          </div>
          <ListField
            label="JVM flags"
            values={d.jvmFlags ?? []}
            onChange={(jvmFlags) => update({ jvmFlags } as Partial<NodeData>)}
          />
        </>
      );
    }
    case "controlPlane":
    case "fafnir":
    case "muninn":
    case "andvari": {
      const d = node.data as RoleData;
      return (
        <>
          <MachineField
            blueprint={blueprint}
            node={node}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "REPLICAS_COLOCATED"])}
          />
          <NumberField
            label="Port"
            value={d.port}
            onChange={(port) => update({ port } as Partial<NodeData>)}
            problems={pick(problems, ["PORT_CONFLICT", "PORT_UNSET", "PORT_RANGE"])}
          />
          {node.kind === "fafnir" && (
            <TextField
              label="Key file"
              value={d.keyFile ?? ""}
              onChange={(keyFile) => update({ keyFile } as Partial<NodeData>)}
            />
          )}
          <ListField
            label="JVM flags"
            values={d.jvmFlags ?? []}
            onChange={(jvmFlags) => update({ jvmFlags } as Partial<NodeData>)}
          />
        </>
      );
    }
    case "agent": {
      const d = node.data as AgentData;
      return (
        <>
          <MachineField
            blueprint={blueprint}
            node={node}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "AGENTS_COLOCATED"])}
          />
          <TextField
            label="Node id"
            value={d.nodeId}
            onChange={(nodeId) => update({ nodeId } as Partial<NodeData>)}
            problems={pick(problems, ["AGENT_NODE_ID_BLANK"])}
          />
          <NumberField
            label="Gossip port"
            value={d.gossipPort}
            onChange={(gossipPort) => update({ gossipPort } as Partial<NodeData>)}
            problems={pick(problems, ["PORT_CONFLICT", "PORT_UNSET", "PORT_RANGE"])}
          />
          <ListField
            label="Labels"
            values={d.labels ?? []}
            onChange={(labels) => update({ labels } as Partial<NodeData>)}
          />
        </>
      );
    }
    case "tenant": {
      const d = node.data as TenantData;
      return (
        <>
          <TextField
            label="Tenant id"
            value={d.id}
            onChange={(id) => update({ id } as Partial<NodeData>)}
          />
          <MemoryBytesField
            label="Quota memory"
            bytes={d.quota.maxMemoryBytes}
            onChange={(maxMemoryBytes) =>
              update({ quota: { ...d.quota, maxMemoryBytes } } as Partial<NodeData>)
            }
            problems={pick(problems, ["QUOTA_EXCEEDED", "QUOTA_NOT_POSITIVE"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <MillicoresField
              label="Quota cpu"
              value={d.quota.maxCpuMillicores}
              onChange={(maxCpuMillicores) =>
                update({ quota: { ...d.quota, maxCpuMillicores } } as Partial<NodeData>)
              }
              problems={pick(problems, ["QUOTA_EXCEEDED", "QUOTA_NOT_POSITIVE"])}
            />
            <NumberField
              label="Max instances"
              value={d.quota.maxInstances}
              onChange={(maxInstances) =>
                update({ quota: { ...d.quota, maxInstances } } as Partial<NodeData>)
              }
              problems={pick(problems, ["QUOTA_EXCEEDED", "QUOTA_NOT_POSITIVE"])}
            />
          </div>
          <SelectField
            label="Isolation posture"
            value={d.isolationPosture ?? "OPEN"}
            options={["OPEN", "DENY_BY_DEFAULT"] as const}
            onChange={(isolationPosture) => update({ isolationPosture } as Partial<NodeData>)}
          />
        </>
      );
    }
    case "service": {
      const d = node.data as ServiceData;
      return (
        <>
          <TextField
            label="Name"
            value={d.name}
            onChange={(name) => update({ name } as Partial<NodeData>)}
          />
          <TenantField
            blueprint={blueprint}
            node={node}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN", "SERVICE_CROSS_TENANT"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <NumberField
              label="Port"
              value={d.port}
              onChange={(port) => update({ port } as Partial<NodeData>)}
              problems={pick(problems, ["SERVICE_PORT_RANGE"])}
            />
            <NumberField
              label="Target port"
              value={d.targetPort}
              onChange={(targetPort) => update({ targetPort } as Partial<NodeData>)}
              problems={pick(problems, ["SERVICE_PORT_RANGE"])}
              hint="Blank defaults to Port."
              allowBlank
            />
          </div>
          <ListField
            label="Deployment names"
            values={d.deploymentNames ?? []}
            options={workloadOptions(blueprint)}
            onChange={(deploymentNames) => update({ deploymentNames } as Partial<NodeData>)}
            problems={pick(problems, ["SERVICE_TARGET_MISSING", "SERVICE_OVERLAP"])}
          />
        </>
      );
    }
    case "networkPolicy": {
      const d = node.data as NetworkPolicyData;
      const ingressRestricted = restrictsIngress(d);
      return (
        <>
          <TextField
            label="Name"
            value={d.name}
            onChange={(name) => update({ name } as Partial<NodeData>)}
          />
          <TenantField
            blueprint={blueprint}
            node={node}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
            freeTextHint="Free text, not a link. Dragging from this node to a tenant on the canvas adds it below as an allowed caller instead -- type the id here to set which tenant this policy itself belongs to."
          />
          <ListField
            label="Deployment names"
            values={d.deploymentNames ?? []}
            options={workloadOptions(blueprint)}
            onChange={(deploymentNames) => update({ deploymentNames } as Partial<NodeData>)}
            problems={pick(problems, ["POLICY_TENANT_WIDE"])}
          />
          <ListField
            label="Service interface names"
            values={d.serviceInterfaceNames ?? []}
            onChange={(serviceInterfaceNames) =>
              update({ serviceInterfaceNames } as Partial<NodeData>)
            }
          />
          <CheckboxField
            label="Restrict inbound callers"
            checked={ingressRestricted}
            onChange={(restrictIngress) => update({ restrictIngress } as Partial<NodeData>)}
          />
          {ingressRestricted ? (
            <ListField
              label="Allowed caller tenants"
              values={d.allowedCallerTenantIds ?? []}
              options={tenantOptions(blueprint)}
              onChange={(allowedCallerTenantIds) =>
                update({ allowedCallerTenantIds } as Partial<NodeData>)
              }
              problems={pick(problems, ["POLICY_ALLOWED_TENANT_UNKNOWN"])}
            />
          ) : (
            <p className="text-[10px] text-muted-foreground">
              Inbound callers are unrestricted. Enable the checkbox above to allow only specific
              tenants, or leave the list empty there to deny every caller.
            </p>
          )}
          <ListField
            label="Allowed callee tenants"
            values={d.allowedCalleeTenantIds ?? []}
            options={tenantOptions(blueprint)}
            onChange={(allowedCalleeTenantIds) =>
              update({ allowedCalleeTenantIds } as Partial<NodeData>)
            }
            problems={pick(problems, ["POLICY_ALLOWED_CALLEE_UNKNOWN"])}
          />
        </>
      );
    }
    case "configEntry": {
      const d = node.data as ConfigEntryData;
      return (
        <>
          <TenantField
            blueprint={blueprint}
            node={node}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
          />
          <TextField
            label="Key"
            value={d.key}
            onChange={(key) => update({ key } as Partial<NodeData>)}
          />
          <TextField
            label="Value"
            value={d.value}
            onChange={(value) => update({ value } as Partial<NodeData>)}
          />
        </>
      );
    }
    case "secret": {
      const d = node.data as SecretData;
      return (
        <>
          <TenantField
            blueprint={blueprint}
            node={node}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
          />
          <TextField
            label="Key"
            value={d.key}
            onChange={(key) => update({ key } as Partial<NodeData>)}
            problems={pick(problems, ["SECRET_NO_VALUE_AT_RUN"])}
          />
        </>
      );
    }
    case "limitRange": {
      const d = node.data as LimitRangeData;
      return (
        <>
          <TenantField
            blueprint={blueprint}
            node={node}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
          />
          <div className="hud-label pt-1">Request bound</div>
          <div className="grid grid-cols-2 gap-2">
            <MemoryField
              label="Min memory"
              value={d.minRequest.memory}
              onChange={(memory) =>
                update({ minRequest: { ...d.minRequest, memory } } as Partial<NodeData>)
              }
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
              allowBlank
            />
            <CpuField
              label="Min cpu"
              value={d.minRequest.cpu}
              onChange={(cpu) =>
                update({ minRequest: { ...d.minRequest, cpu } } as Partial<NodeData>)
              }
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
              allowBlank
            />
            <MemoryField
              label="Max memory"
              value={d.maxRequest.memory}
              onChange={(memory) =>
                update({ maxRequest: { ...d.maxRequest, memory } } as Partial<NodeData>)
              }
              allowBlank
            />
            <CpuField
              label="Max cpu"
              value={d.maxRequest.cpu}
              onChange={(cpu) =>
                update({ maxRequest: { ...d.maxRequest, cpu } } as Partial<NodeData>)
              }
              allowBlank
            />
          </div>
          <div className="hud-label pt-1">Limit bound</div>
          <div className="grid grid-cols-2 gap-2">
            <MemoryField
              label="Min memory"
              value={d.minLimit.memory}
              onChange={(memory) =>
                update({ minLimit: { ...d.minLimit, memory } } as Partial<NodeData>)
              }
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
              allowBlank
            />
            <CpuField
              label="Min cpu"
              value={d.minLimit.cpu}
              onChange={(cpu) => update({ minLimit: { ...d.minLimit, cpu } } as Partial<NodeData>)}
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
              allowBlank
            />
            <MemoryField
              label="Max memory"
              value={d.maxLimit.memory}
              onChange={(memory) =>
                update({ maxLimit: { ...d.maxLimit, memory } } as Partial<NodeData>)
              }
              allowBlank
            />
            <CpuField
              label="Max cpu"
              value={d.maxLimit.cpu}
              onChange={(cpu) => update({ maxLimit: { ...d.maxLimit, cpu } } as Partial<NodeData>)}
              allowBlank
            />
          </div>
        </>
      );
    }
    default:
      return <WorkloadForm blueprint={blueprint} node={node} problems={problems} update={update} />;
  }
}

export function Inspector({ blueprint }: { blueprint: Blueprint }) {
  const selectedId = useBlueprintStore((s) => s.selectedId);
  const removeNode = useBlueprintStore((s) => s.removeNode);
  const select = useBlueprintStore((s) => s.select);
  const width = useUiStore((s) => s.inspectorWidth);
  const setWidth = useUiStore((s) => s.setInspectorWidth);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: MouseEvent) => setWidth(window.innerWidth - e.clientX);
    const onUp = () => setDragging(false);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
    return () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
    };
  }, [dragging, setWidth]);
  const node = blueprint.nodes.find((n) => n.id === selectedId);
  // The combined tier-1 (client-side rules) and tier-2 (Hilmir) findings -- reading only the
  // client-side array here meant a server-only finding never got its own inline field error.
  // Selected as the two raw (referentially stable) arrays and combined in a memo, rather than
  // through the store's own problemsFor/allProblems helpers, which build a fresh array on every
  // call and so are unsafe to call directly inside a zustand selector.
  const ivaldiProblems = useValidationStore((s) => s.problems);
  const serverProblems = useValidationStore((s) => s.serverProblems);
  const problems = useMemo(
    () => [...ivaldiProblems, ...serverProblems].filter((p) => p.nodeId === selectedId),
    [ivaldiProblems, serverProblems, selectedId],
  );
  const collapsed = useUiStore((s) => s.inspectorCollapsed);
  const toggle = useUiStore((s) => s.toggleInspector);

  if (collapsed)
    return (
      <aside className="flex h-full w-8 shrink-0 flex-col items-center border-l border-border bg-sidebar py-2">
        <button
          onClick={toggle}
          title="Show settings"
          className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
        >
          <PanelRightOpen className="size-3.5" />
        </button>
      </aside>
    );

  return (
    <aside
      className="relative flex h-full shrink-0 flex-col border-l border-border bg-sidebar"
      style={{ width }}
    >
      <div
        onMouseDown={() => setDragging(true)}
        title="Drag to resize"
        className="absolute left-0 top-0 z-10 h-full w-1 cursor-ew-resize hover:bg-primary/40"
      />
      <div className="flex items-center justify-between gap-2 border-b border-sidebar-border px-3 py-2">
        <span className="hud-label">{node ? KIND_LABELS[node.kind] : "Settings"}</span>
        <div className="flex items-center gap-2">
          {node && <span className="num text-[10px] text-muted-foreground">{node.id}</span>}
          {node && (
            <button
              onClick={() => select(null)}
              title="Blueprint settings"
              className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
            >
              <Settings2 className="size-3.5" />
            </button>
          )}
          <button
            onClick={toggle}
            title="Hide settings"
            className="rounded-sm border border-border p-1 text-muted-foreground hover:border-primary hover:text-foreground"
          >
            <PanelRightClose className="size-3.5" />
          </button>
        </div>
      </div>
      <div className="flex-1 space-y-3 overflow-y-auto p-3">
        {node ? (
          <>
            {problems.length > 0 && (
              <div className="rounded-sm border border-border bg-secondary/40 p-2">
                <div className="hud-label">Problems</div>
                <ProblemList problems={problems} />
              </div>
            )}
            <NodeErrorBoundary resetKey={node.id}>
              <NodeForm blueprint={blueprint} node={node} problems={problems} />
            </NodeErrorBoundary>
            {(isPlacedRole(node.kind) ||
              isTenantScoped(node.kind) ||
              node.kind === "machine" ||
              // A tenant is itself an edge endpoint -- the target of every belongsTo and
              // allowsCaller edge -- but was neither a placed role nor tenant-scoped (that
              // describes what belongs *to* a tenant, not the tenant node itself), so its own
              // panel showed no Links section at all: nowhere to audit or cut its own memberships.
              node.kind === "tenant") && <LinksSection blueprint={blueprint} node={node} />}
          </>
        ) : (
          <BlueprintSettings blueprint={blueprint} />
        )}
      </div>
      {node && (
        <div className="border-t border-sidebar-border p-3">
          <button
            onClick={() => {
              const links = blueprint.edges.filter(
                (e) => e.source === node.id || e.target === node.id,
              ).length;
              if (
                links > 0 &&
                !window.confirm(
                  `Delete this node and its ${links} link${links === 1 ? "" : "s"}? Use Undo in the toolbar (Ctrl+Z) to bring it back.`,
                )
              )
                return;
              removeNode(node.id);
            }}
            className="inline-flex h-7 w-full items-center justify-center gap-1.5 rounded-sm border border-destructive/50 bg-transparent px-2 font-mono text-[11px] text-destructive hover:bg-destructive/10"
          >
            <Trash2 className="size-3" /> Delete node
          </button>
        </div>
      )}
    </aside>
  );
}
