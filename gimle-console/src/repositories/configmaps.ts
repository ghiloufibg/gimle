import type { ConfigMap } from "@/types";
import {
  ConfigMapConflict,
  configMapsByTenant,
  findConfigMap,
  removeConfigMap,
  upsertConfigMap,
} from "./fixture";
import { delay } from "./util";

export { ConfigMapConflict };

/**
 * A named, multi-key bundle -- unlike {@link ConfigRepository}'s per-key rows, {@link fetchNames}
 * returns just names (the real {@code GET /configmaps/{tenant}} list shape) and a full body is a
 * separate, explicit {@link fetchOne} call, mirroring how {@link SecretsRepository} splits listing
 * metadata from revealing a value.
 */
export interface ConfigMapsRepository {
  fetchNames(tenantId: string): Promise<string[]>;
  fetchOne(tenantId: string, name: string): Promise<ConfigMap>;
  /** Full replace. {@code expectedVersion} omitted means unconditional overwrite; present and
   * stale throws {@link ConfigMapConflict}. */
  upsert(
    tenantId: string,
    name: string,
    data: Record<string, string>,
    expectedVersion?: number,
  ): Promise<ConfigMap>;
  remove(tenantId: string, name: string): Promise<void>;
}

export class MockConfigMapsRepository implements ConfigMapsRepository {
  async fetchNames(tenantId: string): Promise<string[]> {
    return delay((configMapsByTenant[tenantId] ?? []).map((c) => c.name));
  }

  async fetchOne(tenantId: string, name: string): Promise<ConfigMap> {
    const found = findConfigMap(tenantId, name);
    if (!found) throw new Error(`no such configmap: ${name}`);
    return delay(found);
  }

  async upsert(
    tenantId: string,
    name: string,
    data: Record<string, string>,
    expectedVersion?: number,
  ): Promise<ConfigMap> {
    return delay(upsertConfigMap(tenantId, name, data, expectedVersion));
  }

  async remove(tenantId: string, name: string): Promise<void> {
    removeConfigMap(tenantId, name);
    return delay(undefined);
  }
}
