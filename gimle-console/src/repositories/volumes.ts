import type { Volume, VolumeListing } from "@/types";
import { delay } from "./util";

/**
 * A volume is addressed by the `(nodeId, statefulSet, instanceIndex)` triple its owning node's
 * directory layout uses, with the owning tenant travelling as a query parameter rather than a
 * fourth path segment -- omitted unambiguously means the untenanted namespace.
 */
export interface VolumesRepository {
  fetchAll(): Promise<VolumeListing>;
  destroy(
    nodeId: string,
    statefulSet: string,
    instanceIndex: number,
    tenantId?: string | null,
  ): Promise<void>;
}

const mockVolumes: Volume[] = [
  {
    tenantId: "acme",
    statefulSet: "orders-store",
    instanceIndex: 0,
    volumeName: "data",
    usedBytes: 734003200,
    path: "/var/lib/gimle/volumes/acme/orders-store/0/data",
    inUse: true,
    nodeId: "node-a",
    attached: true,
  },
  {
    // A retained orphan: nothing attaches it any more, so it is the one entry destroy accepts.
    tenantId: null,
    statefulSet: "ledger",
    instanceIndex: 3,
    volumeName: "data",
    usedBytes: 12582912,
    path: "/var/lib/gimle/volumes/ledger/3/data",
    inUse: false,
    nodeId: "node-b",
    attached: false,
  },
];

const mockUnreachableNodes: string[] = ["node-c"];

export class MockVolumesRepository implements VolumesRepository {
  async fetchAll(): Promise<VolumeListing> {
    return delay({
      volumes: mockVolumes.map((v) => ({ ...v })),
      unreachableNodes: [...mockUnreachableNodes],
    });
  }

  async destroy(
    nodeId: string,
    statefulSet: string,
    instanceIndex: number,
    _tenantId?: string | null,
  ): Promise<void> {
    const i = mockVolumes.findIndex(
      (v) =>
        v.nodeId === nodeId && v.statefulSet === statefulSet && v.instanceIndex === instanceIndex,
    );
    if (i < 0) throw new Error(`Volume not found: ${statefulSet}[${instanceIndex}] on ${nodeId}`);
    if (mockVolumes[i].attached) {
      throw new Error(
        `volume ${statefulSet}[${instanceIndex}] is still attached on node ${nodeId};` +
          " scale down or delete the statefulset first",
      );
    }
    mockVolumes.splice(i, 1);
    return delay(undefined);
  }
}
