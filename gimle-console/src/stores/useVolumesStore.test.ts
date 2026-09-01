import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  volumesRepo: {
    fetchAll: vi.fn(),
    destroy: vi.fn(),
  },
}));

import { volumesRepo } from "@/repositories";
import { useVolumesStore } from "./useVolumesStore";
import type { Volume } from "@/types";

function volume(overrides: Partial<Volume> = {}): Volume {
  return {
    tenantId: "acme",
    statefulSet: "orders-store",
    instanceIndex: 0,
    volumeName: "data",
    usedBytes: 1024,
    path: "/var/lib/gimle/volumes/acme/orders-store/0/data",
    inUse: false,
    nodeId: "node-a",
    attached: false,
    ...overrides,
  };
}

describe("useVolumesStore", () => {
  beforeEach(() => {
    useVolumesStore.setState({
      volumes: [],
      unreachableNodes: [],
      loading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("load stores the volumes and clears loading", async () => {
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [volume()] });

    await useVolumesStore.getState().load();

    const state = useVolumesStore.getState();
    expect(state.volumes.map((v) => v.statefulSet)).toEqual(["orders-store"]);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("load normalizes an omitted unreachableNodes key to an empty list", async () => {
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [] });

    await useVolumesStore.getState().load();

    expect(useVolumesStore.getState().unreachableNodes).toEqual([]);
  });

  it("load keeps the nodes it could not reach so the screen can say the listing is partial", async () => {
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({
      volumes: [volume()],
      unreachableNodes: ["node-c", "node-d"],
    });

    await useVolumesStore.getState().load();

    expect(useVolumesStore.getState().unreachableNodes).toEqual(["node-c", "node-d"]);
  });

  it("a listing that recovers every node clears a stale unreachable set", async () => {
    useVolumesStore.setState({ unreachableNodes: ["node-c"] });
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [volume()] });

    await useVolumesStore.getState().load();

    expect(useVolumesStore.getState().unreachableNodes).toEqual([]);
  });

  it("load surfaces a rejection as store.error and clears loading", async () => {
    vi.mocked(volumesRepo.fetchAll).mockRejectedValueOnce(new Error("control plane unreachable"));

    await useVolumesStore.getState().load();

    const state = useVolumesStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
  });

  it("a failed refresh keeps the rows it already had rather than blanking the table", async () => {
    useVolumesStore.setState({ volumes: [volume()] });
    vi.mocked(volumesRepo.fetchAll).mockRejectedValueOnce(new Error("boom"));

    await useVolumesStore.getState().load();

    expect(useVolumesStore.getState().volumes).toHaveLength(1);
  });

  it("a successful load clears any previously surfaced error", async () => {
    useVolumesStore.setState({ error: "stale previous error" });
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [] });

    await useVolumesStore.getState().load();

    expect(useVolumesStore.getState().error).toBeNull();
  });

  it("destroy passes the owning tenant through so a tenanted volume is not mis-targeted", async () => {
    vi.mocked(volumesRepo.destroy).mockResolvedValueOnce(undefined);
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [] });

    await useVolumesStore.getState().destroy(volume({ tenantId: "globex", instanceIndex: 2 }));

    expect(volumesRepo.destroy).toHaveBeenCalledWith("node-a", "orders-store", 2, "globex");
  });

  it("destroy passes an explicit null for an untenanted volume, never an omitted tenant", async () => {
    vi.mocked(volumesRepo.destroy).mockResolvedValueOnce(undefined);
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({ volumes: [] });

    await useVolumesStore.getState().destroy(volume({ tenantId: null }));

    expect(volumesRepo.destroy).toHaveBeenCalledWith("node-a", "orders-store", 0, null);
  });

  it("destroy re-reads the listing rather than splicing the row out locally", async () => {
    useVolumesStore.setState({ volumes: [volume(), volume({ instanceIndex: 1 })] });
    vi.mocked(volumesRepo.destroy).mockResolvedValueOnce(undefined);
    vi.mocked(volumesRepo.fetchAll).mockResolvedValueOnce({
      volumes: [volume({ instanceIndex: 1 })],
      unreachableNodes: ["node-c"],
    });

    await useVolumesStore.getState().destroy(volume());

    const state = useVolumesStore.getState();
    expect(volumesRepo.fetchAll).toHaveBeenCalledTimes(1);
    expect(state.volumes.map((v) => v.instanceIndex)).toEqual([1]);
    expect(state.unreachableNodes).toEqual(["node-c"]);
  });

  it("destroy surfaces a refusal as store.error, rethrows it, and leaves the list untouched", async () => {
    useVolumesStore.setState({ volumes: [volume()] });
    vi.mocked(volumesRepo.destroy).mockRejectedValueOnce(new Error("still attached"));

    await expect(useVolumesStore.getState().destroy(volume())).rejects.toThrow("still attached");

    const state = useVolumesStore.getState();
    expect(state.error).toBe("still attached");
    expect(state.volumes).toHaveLength(1);
    expect(volumesRepo.fetchAll).not.toHaveBeenCalled();
  });
});
