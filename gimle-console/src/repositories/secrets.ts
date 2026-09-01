import type { Page, SecretMetadata, SecretType, SecretValue, SecretVersion } from "@/types";
import { removeSecret, secretMetadata, secretsByTenant, upsertSecret } from "./fixture";
import { delay, paginate } from "./util";

/**
 * Fafnir's /secrets/* surface never returns a value alongside metadata --
 * unlike {@link ConfigRepository}'s fetch-everything-at-once shape, {@link fetchPage} here returns
 * metadata only, and revealing a row's value is a separate, explicit {@link fetchValue} call.
 *
 * <p>{@link fetchVersions} returns each version's full record -- author, write timestamp, declared
 * type -- not a bare list of numbers: the version picker is where an operator asks "who put this
 * here", and answering it from the list itself is the whole point of Fafnir recording it.
 */
export interface SecretsRepository {
  fetchPage(args: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<SecretMetadata>>;
  fetchValue(tenantId: string, key: string, version?: number): Promise<SecretValue>;
  fetchVersions(tenantId: string, key: string): Promise<SecretVersion[]>;
  upsert(tenantId: string, key: string, value: string, type?: SecretType): Promise<SecretMetadata>;
  remove(tenantId: string, key: string, destroy: boolean): Promise<void>;
  rotateKey(): Promise<number>;
  /**
   * Stops trusting a master key id outright, returning the id actually retired. Unlike {@link
   * rotateKey}, this is permanently destructive: any ciphertext still encrypted under that id can
   * never be decrypted again.
   */
  retireKey(keyId: number): Promise<number>;
}

let mockActiveKeyId = 1;
const mockRetiredKeyIds = new Set<number>();

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
    const stored = secret.versions[targetVersion - 1];
    if (stored === undefined) throw new Error(`no such version: ${targetVersion}`);
    return delay({
      tenantId,
      key,
      version: targetVersion,
      value: stored.value,
      type: stored.type,
      author: stored.author,
      writtenAtEpochMilli: stored.writtenAtEpochMilli,
    });
  }

  async fetchVersions(tenantId: string, key: string): Promise<SecretVersion[]> {
    const secret = (secretsByTenant[tenantId] ?? []).find((s) => s.key === key);
    if (!secret) throw new Error(`no such secret: ${key}`);
    return delay(
      secret.versions.map((stored, i) => ({
        version: i + 1,
        author: stored.author,
        writtenAtEpochMilli: stored.writtenAtEpochMilli,
        type: stored.type,
      })),
    );
  }

  async upsert(
    tenantId: string,
    key: string,
    value: string,
    type: SecretType = "opaque",
  ): Promise<SecretMetadata> {
    const secret = upsertSecret(tenantId, key, value, type);
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

  async retireKey(keyId: number): Promise<number> {
    if (keyId < 0 || keyId > 255) {
      throw new Error("'keyId' must be between 0 and 255");
    }
    if (keyId === 0) {
      throw new Error("cannot retire the base secrets key");
    }
    if (keyId === mockActiveKeyId) {
      throw new Error(`cannot retire the active secrets key ${keyId}`);
    }
    // Retirement deletes the key file, so a second attempt at the same id is an unknown id rather
    // than a no-op -- the same answer Fafnir gives once that file is gone.
    if (mockRetiredKeyIds.has(keyId)) {
      throw new Error(`no secrets key with id ${keyId}`);
    }
    mockRetiredKeyIds.add(keyId);
    return delay(keyId);
  }
}
