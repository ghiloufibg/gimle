import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  nodesRepo: {
    fetchPage: vi.fn(),
    fetchOne: vi.fn(),
    setCordoned: vi.fn(),
    setTaint: vi.fn(),
  },
}));

import { nodesRepo } from "@/repositories";
import { useNodesStore } from "./useNodesStore";

const BASE_NODE = {
  nodeId: "node-1",
  capabilities: { supportedTiers: ["TIER_1"] as const },
  lastHeartbeatAt: null,
  capacity: {
    totalMemoryBytes: 0,
    assignedMemoryBytes: 0,
    totalCpuMillicores: 0,
    assignedCpuMillicores: 0,
  },
  cordoned: false,
  taints: [] as string[],
};

describe("useNodesStore cordon/taint", () => {
  beforeEach(() => {
    useNodesStore.setState({
      items: [{ ...BASE_NODE }],
      nextCursor: null,
      hasMore: true,
      loading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("setCordoned updates the item in place on success", async () => {
    vi.mocked(nodesRepo.setCordoned).mockResolvedValueOnce(undefined);

    await useNodesStore.getState().setCordoned("node-1", true);

    expect(useNodesStore.getState().items[0].cordoned).toBe(true);
    expect(useNodesStore.getState().error).toBeNull();
  });

  it("setCordoned surfaces a repository rejection as store.error without mutating the item", async () => {
    vi.mocked(nodesRepo.setCordoned).mockRejectedValueOnce(new Error("no such node"));

    await useNodesStore.getState().setCordoned("node-1", true);

    expect(useNodesStore.getState().error).toBe("no such node");
    expect(useNodesStore.getState().items[0].cordoned).toBe(false);
  });

  it("setTaint(true) adds the tenantId, sorted, without duplicating an already-present one", async () => {
    useNodesStore.setState({ items: [{ ...BASE_NODE, taints: ["globex"] }] });
    vi.mocked(nodesRepo.setTaint).mockResolvedValueOnce(undefined);

    await useNodesStore.getState().setTaint("node-1", "acme", true);

    expect(useNodesStore.getState().items[0].taints).toEqual(["acme", "globex"]);
  });

  it("setTaint(false) removes the tenantId", async () => {
    useNodesStore.setState({ items: [{ ...BASE_NODE, taints: ["acme", "globex"] }] });
    vi.mocked(nodesRepo.setTaint).mockResolvedValueOnce(undefined);

    await useNodesStore.getState().setTaint("node-1", "acme", false);

    expect(useNodesStore.getState().items[0].taints).toEqual(["globex"]);
  });

  it("setTaint surfaces a repository rejection as store.error", async () => {
    vi.mocked(nodesRepo.setTaint).mockRejectedValueOnce(new Error("missing tenantId"));

    await useNodesStore.getState().setTaint("node-1", "acme", true);

    expect(useNodesStore.getState().error).toBe("missing tenantId");
  });
});
