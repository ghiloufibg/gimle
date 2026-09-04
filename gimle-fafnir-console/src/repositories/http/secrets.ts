import type { SecretsRepository } from "@/repositories/secrets";
import type { SecretMetadata, SecretValue, SecretVersion } from "@/types";

import { requestJson, requestJsonWithBody, requestOk } from "./apiClient";

function encodeValue(plain: string): string {
  const bytes = new TextEncoder().encode(plain);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function decodeValue(encoded: string): string {
  const binary = atob(encoded);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

const seg = (value: string) => encodeURIComponent(value);

export class HttpSecretsRepository implements SecretsRepository {
  async list(tenantId: string): Promise<SecretMetadata[]> {
    const data = await requestJson<{
      secrets: Array<{ key: string; latestVersion: number; deleted: boolean }>;
    }>(`/secrets/${seg(tenantId)}`);
    return data.secrets.map((secret) => ({ ...secret, tenantId }));
  }

  async read(tenantId: string, key: string, version?: number): Promise<SecretValue> {
    const query = version === undefined ? "" : `?version=${version}`;
    const data = await requestJson<{ value: string; version: number }>(
      `/secrets/${seg(tenantId)}/${seg(key)}${query}`,
    );
    return { tenantId, key, version: data.version, value: decodeValue(data.value) };
  }

  async versions(tenantId: string, key: string): Promise<SecretVersion[]> {
    // The API's own /versions response is an array of {version, author, writtenAtEpochMilli,
    // type} objects, not bare numbers -- returning it unmapped here once meant a caller expecting
    // number[] (e.g. a version-picker <option value>) rendered one of these objects as a raw JSX
    // child instead.
    const data = await requestJson<{ versions: SecretVersion[] }>(
      `/secrets/${seg(tenantId)}/${seg(key)}/versions`,
    );
    return data.versions;
  }

  async upsert(tenantId: string, key: string, value: string): Promise<number> {
    const data = await requestJsonWithBody<{ version: number }>(
      `/secrets/${seg(tenantId)}/${seg(key)}`,
      "PUT",
      { value: encodeValue(value) },
    );
    return data.version;
  }

  async remove(tenantId: string, key: string, destroy = false): Promise<void> {
    const query = destroy ? "?destroy=true" : "";
    await requestOk(`/secrets/${seg(tenantId)}/${seg(key)}${query}`, { method: "DELETE" });
  }

  async rotateKey(): Promise<number> {
    const data = await requestJsonWithBody<{ activeKeyId: number }>(
      "/secrets/rotate-key",
      "POST",
      {},
    );
    return data.activeKeyId;
  }
}
