import type { VolumeListing } from "@/types";
import type { VolumesRepository } from "@/repositories/volumes";
import { requestJson, requestOk, tenantQuery } from "./apiClient";

/**
 * `GET /volumes` and `DELETE /volumes/{nodeId}/{statefulSet}/{instanceIndex}`. The listing is an
 * envelope, not a bare array, because it also reports the nodes it could not reach -- see
 * `VolumeListing`.
 */
export class HttpVolumesRepository implements VolumesRepository {
  async fetchAll(): Promise<VolumeListing> {
    return requestJson<VolumeListing>("GET", "/volumes");
  }

  async destroy(
    nodeId: string,
    statefulSet: string,
    instanceIndex: number,
    tenantId?: string | null,
  ): Promise<void> {
    const path =
      `/volumes/${encodeURIComponent(nodeId)}` +
      `/${encodeURIComponent(statefulSet)}/${instanceIndex}${tenantQuery(tenantId)}`;
    await requestOk("DELETE", path);
  }
}
