import type { ConfigMap } from "@/types";
import type { ConfigMapsRepository } from "@/repositories/configmaps";
import { ConfigMapConflict } from "@/repositories/configmaps";
import { ApiError, requestJson, requestJsonWithBody, requestOk } from "./apiClient";

interface RawConfigMap {
  name: string;
  version: number;
  data: Record<string, string>;
}

/**
 * {@code GET /configmaps/{tenantId}} returns a bare array of names (no {@code tenantId} in the
 * body -- implicit in the URL, the same shape {@link HttpConfigRepository} stitches back onto
 * every plain config entry), and {@code PUT}'s response body carries only {@code {version}} -- the
 * data the caller already sent is echoed back client-side rather than re-fetched.
 */
export class HttpConfigMapsRepository implements ConfigMapsRepository {
  async fetchNames(tenantId: string): Promise<string[]> {
    return requestJson<string[]>("GET", `/configmaps/${encodeURIComponent(tenantId)}`);
  }

  async fetchOne(tenantId: string, name: string): Promise<ConfigMap> {
    const raw = await requestJson<RawConfigMap>(
      "GET",
      `/configmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}`,
    );
    return { tenantId, name: raw.name, version: raw.version, data: raw.data };
  }

  async upsert(
    tenantId: string,
    name: string,
    data: Record<string, string>,
    expectedVersion?: number,
  ): Promise<ConfigMap> {
    const body: { data: Record<string, string>; expectedVersion?: number } = { data };
    if (expectedVersion !== undefined) body.expectedVersion = expectedVersion;
    try {
      const raw = await requestJsonWithBody<{ version: number }>(
        "PUT",
        `/configmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}`,
        body,
      );
      return { tenantId, name, version: raw.version, data };
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const conflict = JSON.parse(e.body) as {
          currentVersion: number;
          currentData: Record<string, string>;
        };
        throw new ConfigMapConflict(conflict.currentVersion, conflict.currentData);
      }
      throw e;
    }
  }

  async remove(tenantId: string, name: string): Promise<void> {
    await requestOk(
      "DELETE",
      `/configmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}`,
    );
  }
}
