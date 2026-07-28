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
}
