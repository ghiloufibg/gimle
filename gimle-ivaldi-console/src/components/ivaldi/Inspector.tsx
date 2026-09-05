import { PanelRightClose, PanelRightOpen, Trash2 } from "lucide-react";

import {
  KIND_LABELS,
  type AgentData,
  type Blueprint,
  type BlueprintNode,
  type ConfigEntryData,
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
} from "@/lib/blueprint";
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
  TextField,
} from "./fields";

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
  node,
  problems,
  update,
}: {
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
      <TextField
        label="Tenant id"
        value={d.tenantId ?? ""}
        onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
        problems={pick(problems, ["TENANT_UNKNOWN", "QUOTA_EXCEEDED"])}
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
          problems={pick(problems, ["JAR_PATH_RELATIVE"])}
        />
      )}
      {(kind === "deployment" || kind === "statefulSet") && (
        <NumberField
          label="Replicas"
          value={d.replicas}
          onChange={(replicas) => update({ replicas } as Partial<NodeData>)}
          problems={pick(problems, ["REPLICAS_NEGATIVE", "ANTI_AFFINITY_SHORT"])}
        />
      )}
      {kind !== "daemonSet" && (
        <CheckboxField
          label="Anti-affinity"
          checked={Boolean(d.placement?.antiAffinity)}
          onChange={(antiAffinity) =>
            update({ placement: { ...d.placement, antiAffinity } } as Partial<NodeData>)
          }
        />
      )}
      <ListField
        label="Required labels"
        values={d.placement?.requiredLabels ?? []}
        onChange={(requiredLabels) =>
          update({ placement: { ...d.placement, requiredLabels } } as Partial<NodeData>)
        }
        problems={pick(problems, ["REQUIRED_LABEL_UNMATCHED", "DAEMONSET_ANTI_AFFINITY"])}
      />
      {kind === "deployment" && (
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
              />
            </div>
          )}
        </>
      )}
      {(kind === "deployment" || kind === "daemonSet") && (
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
                problems={pick(problems, ["DISRUPTION_BOTH_ZERO"])}
              />
              {kind === "deployment" && (
                <NumberField
                  label="Max surge"
                  value={d.disruption.maxSurge}
                  onChange={(maxSurge) =>
                    update({ disruption: { ...d.disruption!, maxSurge } } as Partial<NodeData>)
                  }
                  problems={pick(problems, ["DAEMONSET_MAX_SURGE"])}
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

function NodeForm({ node, problems }: { node: BlueprintNode; problems: Problem[] }) {
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
          <TextField
            label="Machine"
            value={d.machine}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "REPLICAS_COLOCATED"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <NumberField
              label="Raft port"
              value={d.raftPort}
              onChange={(raftPort) => update({ raftPort } as Partial<NodeData>)}
              problems={pick(problems, ["PORT_CONFLICT"])}
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
          <TextField
            label="Machine"
            value={d.machine}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "REPLICAS_COLOCATED"])}
          />
          <NumberField
            label="Port"
            value={d.port}
            onChange={(port) => update({ port } as Partial<NodeData>)}
            problems={pick(problems, ["PORT_CONFLICT"])}
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
          <TextField
            label="Machine"
            value={d.machine}
            onChange={(machine) => update({ machine } as Partial<NodeData>)}
            problems={pick(problems, ["UNKNOWN_MACHINE", "AGENTS_COLOCATED"])}
          />
          <TextField
            label="Node id"
            value={d.nodeId}
            onChange={(nodeId) => update({ nodeId } as Partial<NodeData>)}
          />
          <NumberField
            label="Gossip port"
            value={d.gossipPort}
            onChange={(gossipPort) => update({ gossipPort } as Partial<NodeData>)}
            problems={pick(problems, ["PORT_CONFLICT"])}
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
            problems={pick(problems, ["QUOTA_EXCEEDED"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <MillicoresField
              label="Quota cpu"
              value={d.quota.maxCpuMillicores}
              onChange={(maxCpuMillicores) =>
                update({ quota: { ...d.quota, maxCpuMillicores } } as Partial<NodeData>)
              }
              problems={pick(problems, ["QUOTA_EXCEEDED"])}
            />
            <NumberField
              label="Max instances"
              value={d.quota.maxInstances}
              onChange={(maxInstances) =>
                update({ quota: { ...d.quota, maxInstances } } as Partial<NodeData>)
              }
            />
          </div>
          <SelectField
            label="Isolation posture"
            value={d.isolationPosture ?? "DENY_BY_DEFAULT"}
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
          <TextField
            label="Tenant id"
            value={d.tenantId}
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
            />
          </div>
          <ListField
            label="Deployment names"
            values={d.deploymentNames ?? []}
            onChange={(deploymentNames) => update({ deploymentNames } as Partial<NodeData>)}
            problems={pick(problems, ["SERVICE_TARGET_MISSING"])}
          />
        </>
      );
    }
    case "networkPolicy": {
      const d = node.data as NetworkPolicyData;
      return (
        <>
          <TextField
            label="Name"
            value={d.name}
            onChange={(name) => update({ name } as Partial<NodeData>)}
          />
          <TextField
            label="Tenant id"
            value={d.tenantId}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
          />
          <ListField
            label="Deployment names"
            values={d.deploymentNames ?? []}
            onChange={(deploymentNames) => update({ deploymentNames } as Partial<NodeData>)}
            problems={pick(problems, ["POLICY_NO_DIRECTION"])}
          />
          <ListField
            label="Allowed caller tenants"
            values={d.allowedCallerTenantIds ?? []}
            onChange={(allowedCallerTenantIds) =>
              update({ allowedCallerTenantIds } as Partial<NodeData>)
            }
            problems={pick(problems, ["POLICY_ALLOWED_TENANT_UNKNOWN"])}
          />
        </>
      );
    }
    case "configEntry": {
      const d = node.data as ConfigEntryData;
      return (
        <>
          <TextField
            label="Tenant id"
            value={d.tenantId}
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
          <TextField
            label="Tenant id"
            value={d.tenantId}
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
          <TextField
            label="Tenant id"
            value={d.tenantId}
            onChange={(tenantId) => update({ tenantId } as Partial<NodeData>)}
            problems={pick(problems, ["TENANT_UNKNOWN"])}
          />
          <div className="grid grid-cols-2 gap-2">
            <MemoryField
              label="Min memory"
              value={d.min.memory}
              onChange={(memory) => update({ min: { ...d.min, memory } } as Partial<NodeData>)}
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
            />
            <CpuField
              label="Min cpu"
              value={d.min.cpu}
              onChange={(cpu) => update({ min: { ...d.min, cpu } } as Partial<NodeData>)}
              problems={pick(problems, ["LIMITRANGE_VIOLATION"])}
            />
            <MemoryField
              label="Max memory"
              value={d.max.memory}
              onChange={(memory) => update({ max: { ...d.max, memory } } as Partial<NodeData>)}
            />
            <CpuField
              label="Max cpu"
              value={d.max.cpu}
              onChange={(cpu) => update({ max: { ...d.max, cpu } } as Partial<NodeData>)}
            />
          </div>
        </>
      );
    }
    default:
      return <WorkloadForm node={node} problems={problems} update={update} />;
  }
}

export function Inspector({ blueprint }: { blueprint: Blueprint }) {
  const selectedId = useBlueprintStore((s) => s.selectedId);
  const removeNode = useBlueprintStore((s) => s.removeNode);
  const width = 360;
  const allProblems = useValidationStore((s) => s.problems);
  const node = blueprint.nodes.find((n) => n.id === selectedId);
  const problems = allProblems.filter((p) => p.nodeId === selectedId);
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
      className="flex h-full shrink-0 flex-col border-l border-border bg-sidebar"
      style={{ width }}
    >
      <div className="flex items-center justify-between gap-2 border-b border-sidebar-border px-3 py-2">
        <span className="hud-label">{node ? KIND_LABELS[node.kind] : "Settings"}</span>
        <div className="flex items-center gap-2">
          {node && <span className="num text-[10px] text-muted-foreground">{node.id}</span>}
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
              <NodeForm node={node} problems={problems} />
            </NodeErrorBoundary>
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
