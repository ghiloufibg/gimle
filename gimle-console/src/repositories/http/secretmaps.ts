import type { SecretMap, SecretMapKeyResult } from "@/types";
import type { SecretMapsRepository } from "@/repositories/secretmaps";
import { requestJson, requestJsonWithBody, requestOk } from "./apiClient";

interface RawSecretMapKeyMetadata {
  key: string;
  latestVersion: number;
  deleted: boolean;
}

/**
 * {@code GET /secretmaps/{tenantId}} returns {@code {names: string[]}}, and {@code GET
 * /secretmaps/{tenantId}/{name}} returns {@code {name, keys: [...]}} -- per-key metadata only,
 * never a value alongside it, the identical posture {@link HttpSecretsRepository} already takes
 * for the flat surface. {@code set}'s response body carries one {@link SecretMapKeyResult} per
 * key sent, reported independently rather than a single all-or-nothing outcome, since each key
 * keeps its own version ledger with no group-level version to conflict-check against.
 */
export class HttpSecretMapsRepository implements SecretMapsRepository {
  async fetchNames(tenantId: string): Promise<string[]> {
    const raw = await requestJson<{ names: string[] }>(
      "GET",
      `/secretmaps/${encodeURIComponent(tenantId)}`,
    );
    return raw.names;
  }

  async fetchOne(tenantId: string, name: string): Promise<SecretMap> {
    const raw = await requestJson<{ name: string; keys: RawSecretMapKeyMetadata[] }>(
      "GET",
      `/secretmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}`,
    );
    return { tenantId, name: raw.name, keys: raw.keys };
  }

  async set(
    tenantId: string,
    name: string,
    data: Record<string, string>,
  ): Promise<SecretMapKeyResult[]> {
    const encoded: Record<string, string> = {};
    for (const [key, value] of Object.entries(data)) {
      encoded[key] = encodeBase64(value);
    }
    const raw = await requestJsonWithBody<{ results: SecretMapKeyResult[] }>(
      "PUT",
      `/secretmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}`,
      { data: encoded },
    );
    return raw.results;
  }

  async remove(tenantId: string, name: string, destroy?: boolean): Promise<void> {
    const query = destroy ? "?destroy=true" : "";
    await requestOk(
      "DELETE",
      `/secretmaps/${encodeURIComponent(tenantId)}/${encodeURIComponent(name)}${query}`,
    );
  }
}

function encodeBase64(value: string): string {
  return btoa(String.fromCharCode(...new TextEncoder().encode(value)));
}
