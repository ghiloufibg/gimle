import type { Blueprint, BlueprintNode, NodeKind } from "./blueprint";
import type { AgentData, RoleData, StoreData } from "./blueprint";

export const DEFAULT_PORTS: Record<string, number> = {
  storeRaft: 9080,
  storeClient: 9091,
  controlPlane: 8080,
  fafnir: 9092,
  muninn: 9093,
  andvari: 9094,
  agent: 9090,
};

export interface PortClaim {
  nodeId: string;
  machine: string;
  port: number;
  what: string;
}

export function machineOf(node: BlueprintNode): string {
  const d = node.data as { machine?: string };
  return d.machine ?? "";
}

export function portClaims(bp: Blueprint): PortClaim[] {
  const claims: PortClaim[] = [];
  for (const n of bp.nodes) {
    const machine = machineOf(n);
    if (n.kind === "store") {
      const d = n.data as StoreData;
      claims.push({ nodeId: n.id, machine, port: d.raftPort, what: "store raft" });
      claims.push({ nodeId: n.id, machine, port: d.clientPort, what: "store client" });
    } else if (n.kind === "agent") {
      const d = n.data as AgentData;
      claims.push({ nodeId: n.id, machine, port: d.gossipPort, what: "agent gossip" });
    } else if (["controlPlane", "fafnir", "muninn", "andvari"].includes(n.kind)) {
      const d = n.data as RoleData;
      claims.push({ nodeId: n.id, machine, port: d.port, what: `${n.kind} port` });
    }
  }
  return claims;
}

export function portConflicts(bp: Blueprint): PortClaim[][] {
  const byKey = new Map<string, PortClaim[]>();
  for (const c of portClaims(bp)) {
    const key = `${c.machine}:${c.port}`;
    byKey.set(key, [...(byKey.get(key) ?? []), c]);
  }
  return [...byKey.values()].filter((g) => g.length > 1);
}

export function defaultPortFor(kind: NodeKind): number | undefined {
  switch (kind) {
    case "controlPlane":
      return DEFAULT_PORTS.controlPlane;
    case "fafnir":
      return DEFAULT_PORTS.fafnir;
    case "muninn":
      return DEFAULT_PORTS.muninn;
    case "andvari":
      return DEFAULT_PORTS.andvari;
    default:
      return undefined;
  }
}
