import type { Page, SecretMetadata, SecretValue } from "@/types";
import { removeSecret, secretMetadata, secretsByTenant, upsertSecret } from "./fixture";
import { delay, paginate } from "./util";

/**
 * Fafnir's /secrets/* surface (design doc §6e/§7) never returns a value alongside metadata --
 * unlike {@link ConfigRepository}'s fetch-everything-at-once shape, {@link fetchPage} here returns
 * metadata only, and revealing a row's value is a separate, explicit {@link fetchValue} call.
 */
export interface SecretsRepository {
  fetchPage(args: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<SecretMetadata>>;
  fetchValue(tenantId: string, key: string, version?: number): Promise<SecretValue>;
  fetchVersions(tenantId: string, key: string): Promise<number[]>;
  upsert(tenantId: string, key: string, value: string): Promise<SecretMetadata>;
  remove(tenantId: string, key: string, destroy: boolean): Promise<void>;
  rotateKey(): Promise<number>;
}

let mockActiveKeyId = 1;

export class MockSecretsRepository implements SecretsRepository {
  async fetchPage({
    tenantId,
    cursor,
    pageSize,
  }: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }) {
    const list = (secretsByTenant[tenantId] ?? []).map(secretMetadata);
    return delay(paginate(list, cursor, pageSize));
  }

  async fetchValue(tenantId: string, key: string, version?: number): Promise<SecretValue> {
    const secret = (secretsByTenant[tenantId] ?? []).find((s) => s.key === key);
    if (!secret) throw new Error(`no such secret: ${key}`);
    const targetVersion = version ?? secret.versions.length;
    const value = secret.versions[targetVersion - 1];
    if (value === undefined) throw new Error(`no such version: ${targetVersion}`);
    return delay({ tenantId, key, version: targetVersion, value });
  }

  async fetchVersions(tenantId: string, key: string): Promise<number[]> {
    const secret = (secretsByTenant[tenantId] ?? []).find((s) => s.key === key);
    if (!secret) throw new Error(`no such secret: ${key}`);
    return delay(secret.versions.map((_, i) => i + 1));
  }

  async upsert(tenantId: string, key: string, value: string): Promise<SecretMetadata> {
    const secret = upsertSecret(tenantId, key, value);
    return delay(secretMetadata(secret));
  }

  async remove(tenantId: string, key: string, destroy: boolean): Promise<void> {
    removeSecret(tenantId, key, destroy);
    return delay(undefined);
  }

  async rotateKey(): Promise<number> {
    mockActiveKeyId += 1;
    return delay(mockActiveKeyId);
  }
}
