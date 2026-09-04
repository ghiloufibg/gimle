import { pemProblem } from "@/lib/secretType";
import type { SecretMetadata, SecretType, SecretValue, SecretVersion } from "@/types";

export interface SecretsRepository {
  list(tenantId: string): Promise<SecretMetadata[]>;
  read(tenantId: string, key: string, version?: number): Promise<SecretValue>;
  versions(tenantId: string, key: string): Promise<SecretVersion[]>;
  upsert(tenantId: string, key: string, value: string, type?: SecretType): Promise<number>;
  remove(tenantId: string, key: string, destroy?: boolean): Promise<void>;
  rotateKey(): Promise<number>;
}

const delay = (ms = 220) => new Promise((resolve) => setTimeout(resolve, ms));

interface MockVersion {
  value: string;
  type: SecretType;
}

interface MockEntry {
  deleted: boolean;
  versions: MockVersion[];
}

const opaque = (value: string): MockVersion => ({ value, type: "opaque" });

export class MockSecretsRepository implements SecretsRepository {
  private activeKeyId = 7;
  private store = new Map<string, Map<string, MockEntry>>([
    [
      "asgard",
      new Map<string, MockEntry>([
        ["db/password", { deleted: false, versions: [opaque("hunter2"), opaque("bifrost-9x")] }],
        ["api/token", { deleted: false, versions: [opaque("tok_valhalla_01")] }],
        ["legacy/key", { deleted: true, versions: [opaque("retired")] }],
      ]),
    ],
    [
      "midgard",
      new Map<string, MockEntry>([
        ["smtp/password", { deleted: false, versions: [opaque("mail-secret")] }],
      ]),
    ],
  ]);

  private tenant(tenantId: string) {
    let tenant = this.store.get(tenantId);
    if (!tenant) {
      tenant = new Map<string, MockEntry>();
      this.store.set(tenantId, tenant);
    }
    return tenant;
  }

  async list(tenantId: string): Promise<SecretMetadata[]> {
    await delay();
    return [...this.tenant(tenantId).entries()].map(([key, entry]) => ({
      tenantId,
      key,
      latestVersion: entry.versions.length,
      deleted: entry.deleted,
    }));
  }

  async read(tenantId: string, key: string, version?: number): Promise<SecretValue> {
    await delay(160);
    const entry = this.tenant(tenantId).get(key);
    if (!entry) throw new Error("no such secret");
    const resolved = version ?? entry.versions.length;
    const stored = entry.versions[resolved - 1];
    if (stored === undefined) throw new Error("no such secret");
    return { tenantId, key, version: resolved, value: stored.value, type: stored.type };
  }

  async versions(tenantId: string, key: string): Promise<SecretVersion[]> {
    await delay(120);
    const entry = this.tenant(tenantId).get(key);
    if (!entry) throw new Error("no such secret");
    return entry.versions.map((stored, index) => ({
      version: index + 1,
      author: "mock",
      writtenAtEpochMilli: Date.now(),
      type: stored.type,
    }));
  }

  async upsert(
    tenantId: string,
    key: string,
    value: string,
    type: SecretType = "opaque",
  ): Promise<number> {
    await delay();
    const problem = pemProblem(type, value);
    if (problem) throw new Error(problem);
    const tenant = this.tenant(tenantId);
    const entry = tenant.get(key) ?? { deleted: false, versions: [] };
    entry.versions.push({ value, type });
    entry.deleted = false;
    tenant.set(key, entry);
    return entry.versions.length;
  }

  async remove(tenantId: string, key: string, destroy = false): Promise<void> {
    await delay();
    const tenant = this.tenant(tenantId);
    const entry = tenant.get(key);
    if (!entry) throw new Error("no such secret");
    if (destroy) tenant.delete(key);
    else entry.deleted = true;
  }

  async rotateKey(): Promise<number> {
    await delay(400);
    this.activeKeyId += 1;
    return this.activeKeyId;
  }
}
