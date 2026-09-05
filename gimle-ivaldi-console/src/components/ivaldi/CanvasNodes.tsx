import { Handle, Position, type NodeProps } from "@xyflow/react";

import {
  KIND_LABELS,
  type AgentData,
  type ConfigEntryData,
  type LimitRangeData,
  type MachineData,
  type NodeKind,
  type Problem,
  type RoleData,
  type SecretData,
  type ServiceData,
  type StoreData,
  type TenantData,
  type WorkloadData,
} from "@/lib/blueprint";
import { formatMemory } from "@/lib/units";
import { cn } from "@/lib/utils";

import { KIND_META } from "./kinds";

export interface IvaldiNodeData extends Record<string, unknown> {
  kind: NodeKind;
  label: string;
  fact: string;
  problems: Problem[];
  selected: boolean;
  /** Effective placement: the machine or tenant the generated files will carry. */
  where?: string;
}

function stripeClass(problems: Problem[]): string {
  if (problems.some((p) => p.severity === "error")) return "bg-status-bad";
  if (problems.some((p) => p.severity === "warning")) return "bg-status-warn";
  if (problems.some((p) => p.severity === "info")) return "bg-status-info";
  return "bg-transparent";
}

export function keyFact(kind: NodeKind, data: unknown): string {
  switch (kind) {
    case "machine":
      return (data as MachineData).host;
    case "store": {
      const d = data as StoreData;
      return `raft ${d.raftPort} · client ${d.clientPort}`;
    }
    case "agent": {
      const d = data as AgentData;
      return `${d.nodeId} · gossip ${d.gossipPort}${d.labels?.length ? ` · ${d.labels.join(",")}` : ""}`;
    }
    case "controlPlane":
    case "fafnir":
    case "muninn":
    case "andvari":
      return `port ${(data as RoleData).port}`;
    case "tenant": {
      const d = data as TenantData;
      return `${formatMemory(d.quota.maxMemoryBytes)} · ${d.quota.maxCpuMillicores}m · ${d.quota.maxInstances} inst`;
    }
    case "deployment":
    case "statefulSet":
      return `${(data as WorkloadData).replicas ?? 0} replicas`;
    case "daemonSet":
      return "one per agent";
    case "job":
      return "run to completion";
    case "cronJob":
      return (data as WorkloadData).schedule ?? "";
    case "service": {
      const d = data as ServiceData;
      return `${d.port} -> ${d.targetPort}`;
    }
    case "networkPolicy":
      return "policy";
    case "configEntry": {
      const d = data as ConfigEntryData;
      return `${d.key}=${d.value}`;
    }
    case "secret":
      return (data as SecretData).key;
    case "limitRange": {
      const d = data as LimitRangeData;
      return `${d.min.memory}/${d.min.cpu} – ${d.max.memory}/${d.max.cpu}`;
    }
    default:
      return "";
  }
}

export function MachineNode({ data }: NodeProps) {
  const d = data as IvaldiNodeData;
  return (
    // pointer-events-none on the frame itself: this box is drawn under every role placed on it
    // (see the machines-first sort in DesignerCanvas), but the edges pane sits under ALL nodes
    // regardless of zIndex, so without this a placedOn/belongsTo edge routed anywhere under this
    // 640x260 frame was un-clickable -- occluded by a div with nothing visible to click through.
    // Only the bits a user actually needs to grab (the header, to drag/select the machine; the
    // handles, to draw a link; the status stripe, to read/click it) opt back in explicitly.
    <div
      className={cn(
        "relative h-[260px] w-[640px] rounded-sm border border-dashed border-primary/50 bg-primary/5 pointer-events-none",
        d.selected && "border-primary ring-1 ring-primary",
      )}
    >
      <div className="pointer-events-auto absolute left-2 top-1.5 flex items-center gap-2">
        <span className="hud-label">Machine</span>
        <span className="font-mono text-[11px] font-semibold text-foreground">{d.label}</span>
        <span className="num text-[10px] text-muted-foreground">{d.fact}</span>
      </div>
      <div
        className={cn(
          "pointer-events-auto absolute left-0 top-0 h-full w-[3px] rounded-l-sm",
          stripeClass(d.problems),
        )}
      />
      <Handle
        type="target"
        position={Position.Left}
        className="!size-2 !bg-primary pointer-events-auto"
      />
      <Handle
        type="source"
        position={Position.Right}
        className="!size-2 !bg-primary pointer-events-auto"
      />
    </div>
  );
}

export function ResourceNode({ data }: NodeProps) {
  const d = data as IvaldiNodeData;
  const Icon = KIND_META[d.kind].icon;
  return (
    <div
      className={cn(
        "relative min-w-[190px] max-w-[230px] overflow-hidden rounded-sm border border-border bg-card px-2.5 py-1.5 shadow-sm",
        d.selected && "border-primary ring-1 ring-primary",
      )}
    >
      <div className={cn("absolute left-0 top-0 h-full w-[3px]", stripeClass(d.problems))} />
      <div className="flex items-center gap-1.5 pl-1">
        <Icon className="size-3 text-primary" />
        <span className="hud-label">{KIND_LABELS[d.kind]}</span>
      </div>
      <div className="truncate pl-1 font-mono text-[12px] font-semibold text-card-foreground">
        {d.label}
      </div>
      <div className="num truncate pl-1 text-[10px] text-muted-foreground">{d.fact}</div>
      {d.where && (
        <div className="num truncate pl-1 text-[10px] text-muted-foreground/80">{d.where}</div>
      )}
      <Handle type="target" position={Position.Left} className="!size-2 !bg-primary" />
      <Handle type="source" position={Position.Right} className="!size-2 !bg-primary" />
    </div>
  );
}

export const nodeTypes = { machine: MachineNode, resource: ResourceNode };
