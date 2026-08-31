import type { Node, Page } from "@/types";
import { nodes } from "./fixture";
import { isStale } from "@/lib/format";
import { delay, paginate } from "./util";

export interface NodesSummary {
  total: number;
  stale: number;
  recent: Node[];
}

export interface NodesRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<Node>>;
  fetchOne(nodeId: string): Promise<Node>;
  fetchSummary(): Promise<NodesSummary>;
  setCordoned(nodeId: string, cordoned: boolean): Promise<void>;
  setTaint(nodeId: string, tenantId: string, tainted: boolean): Promise<void>;
}

export class MockNodesRepository implements NodesRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(nodes, cursor, pageSize));
  }
  async fetchOne(nodeId: string) {
    const n = nodes.find((x) => x.nodeId === nodeId);
    if (!n) throw new Error(`Node not found: ${nodeId}`);
    return delay(n);
  }
  async fetchSummary() {
    return delay<NodesSummary>({
      total: nodes.length,
      stale: nodes.filter((n) => isStale(n.lastHeartbeatAt)).length,
      recent: nodes.slice(0, 8),
    });
  }
  async setCordoned(nodeId: string, cordoned: boolean) {
    const n = nodes.find((x) => x.nodeId === nodeId);
    if (!n) throw new Error(`Node not found: ${nodeId}`);
    n.cordoned = cordoned;
    return delay(undefined);
  }
  async setTaint(nodeId: string, tenantId: string, tainted: boolean) {
    const n = nodes.find((x) => x.nodeId === nodeId);
    if (!n) throw new Error(`Node not found: ${nodeId}`);
    n.taints = tainted
      ? [...new Set([...n.taints, tenantId])].sort()
      : n.taints.filter((t) => t !== tenantId);
    return delay(undefined);
  }
}
