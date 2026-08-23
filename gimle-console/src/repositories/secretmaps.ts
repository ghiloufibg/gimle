import type { SecretMap, SecretMapKeyResult } from "@/types";
import {
  findSecretMap,
  removeSecretMap,
  secretMapsByTenant,
  upsertSecretMap,
} from "./fixture";
import { delay } from "./util";

/**
 * The SecretMap kind's own repository interface, mirroring {@code ConfigMapsRepository}'s shape
 * but without a single object-level version: {@link set} reports one outcome per key (see {@link
 * SecretMapKeyResult}) rather than a single new version, since each key keeps its own independent
 * version ledger (see {@code com.gimle.fafnir.secretmap.SecretMapStore}).
 */
export interface SecretMapsRepository {
  fetchNames(tenantId: string): Promise<string[]>;
  fetchOne(tenantId: string, name: string): Promise<SecretMap>;
  set(tenantId: string, name: string, data: Record<string, string>): Promise<SecretMapKeyResult[]>;
  remove(tenantId: string, name: string, destroy?: boolean): Promise<void>;
}

export class MockSecretMapsRepository implements SecretMapsRepository {
  async fetchNames(tenantId: string): Promise<string[]> {
    return delay((secretMapsByTenant[tenantId] ?? []).map((s) => s.name));
  }

  async fetchOne(tenantId: string, name: string): Promise<SecretMap> {
    const found = findSecretMap(tenantId, name);
    if (!found) throw new Error(`no such secretmap: ${name}`);
    return delay(found);
  }

  async set(
    tenantId: string,
    name: string,
    data: Record<string, string>,
  ): Promise<SecretMapKeyResult[]> {
    return delay(upsertSecretMap(tenantId, name, data));
  }

  async remove(tenantId: string, name: string): Promise<void> {
    removeSecretMap(tenantId, name);
    return delay(undefined);
  }
}
