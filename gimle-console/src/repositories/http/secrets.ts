import type { SecretMetadata, SecretValue, Page } from "@/types";
import type { SecretsRepository } from "@/repositories/secrets";
import { requestJson, requestJsonWithBody, requestOk } from "./apiClient";

interface RawSecretMetadata {
  key: string;
  latestVersion: number;
  deleted: boolean;
}

/**
 * Deliberately uncached, the same as {@link HttpConfigRepository}: {@link fetchPage} only ever
 * fetches metadata (Fafnir's own {@code /secrets/{tenantId}} list response never carries a
 * {@code value}), and revealing a row's value is a distinct, on-demand {@link
 * fetchValue} call rather than something already present in the list.
 *
 * <p>{@code value} crosses the wire as base64 (Fafnir's own body shape is binary-safe, unlike
 * {@code /config/*}'s plain-string {@code value} field) -- this class is the one place that
 * encoding is visible at all; every caller above it works with plain strings.
 */
export class HttpSecretsRepository implements SecretsRepository {
  async fetchPage({
    tenantId,
    cursor,
    pageSize,
  }: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<SecretMetadata>> {
    const raw = await requestJson<{ secrets: RawSecretMetadata[] }>(
      "GET",
      `/secrets/${encodeURIComponent(tenantId)}`,
    );
    const all: SecretMetadata[] = raw.secrets.map((s) => ({ ...s, tenantId }));
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  async fetchValue(tenantId: string, key: string, version?: number): Promise<SecretValue> {
    const query = version === undefined ? "" : `?version=${version}`;
    const raw = await requestJson<{ value: string; version: number }>(
      "GET",
      `/secrets/${encodeURIComponent(tenantId)}/${encodeURIComponent(key)}${query}`,
    );
    return { tenantId, key, version: raw.version, value: decodeBase64(raw.value) };
  }

  async fetchVersions(tenantId: string, key: string): Promise<number[]> {
    const raw = await requestJson<{ versions: number[] }>(
      "GET",
      `/secrets/${encodeURIComponent(tenantId)}/${encodeURIComponent(key)}/versions`,
    );
    return raw.versions;
  }

  async upsert(tenantId: string, key: string, value: string): Promise<SecretMetadata> {
    const raw = await requestJsonWithBody<{ version: number }>(
      "PUT",
      `/secrets/${encodeURIComponent(tenantId)}/${encodeURIComponent(key)}`,
      { value: encodeBase64(value) },
    );
    return { tenantId, key, latestVersion: raw.version, deleted: false };
  }

  async remove(tenantId: string, key: string, destroy: boolean): Promise<void> {
    const query = destroy ? "?destroy=true" : "";
    await requestOk(
      "DELETE",
      `/secrets/${encodeURIComponent(tenantId)}/${encodeURIComponent(key)}${query}`,
    );
  }

  async rotateKey(): Promise<number> {
    const raw = await requestJsonWithBody<{ activeKeyId: number }>(
      "POST",
      "/secrets/rotate-key",
      {},
    );
    return raw.activeKeyId;
  }
}

function encodeBase64(value: string): string {
  return btoa(String.fromCharCode(...new TextEncoder().encode(value)));
}

function decodeBase64(value: string): string {
  return new TextDecoder().decode(Uint8Array.from(atob(value), (c) => c.charCodeAt(0)));
}
