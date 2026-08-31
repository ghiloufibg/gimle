import type { Node, Page } from "@/types";
import type { NodesRepository, NodesSummary } from "@/repositories/nodes";
import { isStale } from "@/lib/format";
import { requestJson, requestOk } from "./apiClient";

// `capacity` and `lastHeartbeatAt` are only present once a node's first heartbeat has landed
// (ApiServer.java's handleNodesList puts both inside the same `ifPresent`, or neither) -- both
// normalized below so the frontend's non-optional `Node` type always has something to render.
// `lastHeartbeatAt` in particular must become a real `null`, not stay `undefined`: routes/metrics.tsx
// does a strict `=== null` check to mean "never heartbeated," which `undefined` would silently fail.
interface RawNode {
  nodeId: string;
  capabilities: { supportedTiers: Node["capabilities"]["supportedTiers"] };
  lastHeartbeatAt?: string | null;
  capacity?: Node["capacity"];
  cordoned?: boolean;
  taints?: string[];
}

const NO_CAPACITY: Node["capacity"] = {
  totalMemoryBytes: 0,
  assignedMemoryBytes: 0,
  totalCpuMillicores: 0,
  assignedCpuMillicores: 0,
};

function mapNode(raw: RawNode): Node {
  return {
    ...raw,
    lastHeartbeatAt: raw.lastHeartbeatAt ?? null,
    capacity: raw.capacity ?? NO_CAPACITY,
    cordoned: raw.cordoned ?? false,
    taints: raw.taints ?? [],
  };
}

export class HttpNodesRepository implements NodesRepository {
  private cache: Node[] | null = null;

  private async all(forceRefresh: boolean): Promise<Node[]> {
    if (forceRefresh || this.cache === null) {
      const raw = await requestJson<RawNode[]>("GET", "/nodes");
      this.cache = raw.map(mapNode);
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<Node>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  async fetchOne(nodeId: string): Promise<Node> {
    const all = await this.all(false);
    const n = all.find((x) => x.nodeId === nodeId);
    if (n) return n;
    // Not in the cached snapshot (e.g. registered after the last fetch) -- one forced refresh
    // before giving up, matching the mock's "throw if truly absent" contract.
    const fresh = await this.all(true);
    const found = fresh.find((x) => x.nodeId === nodeId);
    if (!found) throw new Error(`Node not found: ${nodeId}`);
    return found;
  }

  async fetchSummary(): Promise<NodesSummary> {
    const all = await this.all(false);
    return {
      total: all.length,
      stale: all.filter((n) => isStale(n.lastHeartbeatAt)).length,
      recent: all.slice(0, 8),
    };
  }

  async setCordoned(nodeId: string, cordoned: boolean): Promise<void> {
    // Empty-body POST, matching the CLI's own cordon/uncordon calls -- requestOk omits the body
    // entirely when none is passed.
    await requestOk(
      "POST",
      `/nodes/${encodeURIComponent(nodeId)}/${cordoned ? "cordon" : "uncordon"}`,
    );
    this.cache = null;
  }

  async setTaint(nodeId: string, tenantId: string, tainted: boolean): Promise<void> {
    await requestOk(
      "POST",
      `/nodes/${encodeURIComponent(nodeId)}/${tainted ? "taint" : "untaint"}`,
      { tenantId },
    );
    this.cache = null;
  }
}
