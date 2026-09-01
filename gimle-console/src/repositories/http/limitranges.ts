import type { LimitRange } from "@/types";
import type { LimitRangesRepository } from "@/repositories/limitranges";
import { requestJson, requestOk } from "./apiClient";

/**
 * `GET /limitranges` (flat array, no pagination) plus `GET`/`PUT`/`DELETE /limitranges/{tenantId}`.
 * A LimitRange is keyed by tenant id alone -- it has no name of its own and no `?tenant=` query
 * parameter, unlike every by-name resource here -- so the tenant travels as the path segment.
 *
 * `save` strips `tenantId` out of the body: the path already carries the identity, and the server
 * builds the stored spec from the path segment, never from the body's own copy of it.
 */
export class HttpLimitRangesRepository implements LimitRangesRepository {
  async fetchAll(): Promise<LimitRange[]> {
    return requestJson<LimitRange[]>("GET", "/limitranges");
  }

  async fetchOne(tenantId: string): Promise<LimitRange> {
    return requestJson<LimitRange>("GET", `/limitranges/${encodeURIComponent(tenantId)}`);
  }

  async save(spec: LimitRange): Promise<void> {
    const { tenantId, ...bounds } = spec;
    await requestOk("PUT", `/limitranges/${encodeURIComponent(tenantId)}`, bounds);
  }

  async remove(tenantId: string): Promise<void> {
    await requestOk("DELETE", `/limitranges/${encodeURIComponent(tenantId)}`);
  }
}
