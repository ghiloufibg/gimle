import type {
  SecretMap,
  SecretMapGroupVersion,
  SecretMapKeyResult,
  SecretMapRollbackResult,
} from "@/types";
import {
  findSecretMap,
  listSecretMapGroupVersions,
  removeSecretMap,
  rollbackSecretMap,
  secretMapsByTenant,
  upsertSecretMap,
} from "./fixture";
import { delay } from "./util";

/**
 * The SecretMap kind's own repository interface, mirroring {@code ConfigMapsRepository}'s shape
 * but without a single object-level version: {@link set} reports one outcome per key (see {@link
 * SecretMapKeyResult}) rather than a single new version, since each key keeps its own independent
 * version ledger (see {@code com.gimle.fafnir.secretmap.SecretMapStore}). {@link fetchGroupVersions}
 * and {@link rollback} are that same store's own group-level ledger: every write stamps a new
 * group version recording the full member state, and rollback restores a chosen one as a
 * brand-new group version, never rewriting history.
 */
export interface SecretMapsRepository {
  fetchNames(tenantId: string): Promise<string[]>;
  fetchOne(tenantId: string, name: string): Promise<SecretMap>;
  set(tenantId: string, name: string, data: Record<string, string>): Promise<SecretMapKeyResult[]>;
  remove(tenantId: string, name: string, destroy?: boolean): Promise<void>;
  fetchGroupVersions(tenantId: string, name: string): Promise<SecretMapGroupVersion[]>;
  rollback(tenantId: string, name: string, groupVersion: number): Promise<SecretMapRollbackResult>;
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

  async fetchGroupVersions(tenantId: string, name: string): Promise<SecretMapGroupVersion[]> {
    return delay(listSecretMapGroupVersions(tenantId, name));
  }

  async rollback(
    tenantId: string,
    name: string,
    groupVersion: number,
  ): Promise<SecretMapRollbackResult> {
    return delay(rollbackSecretMap(tenantId, name, groupVersion));
  }
}
