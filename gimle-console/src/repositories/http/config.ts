import type { ConfigEntry, Page } from "@/types";
import type { ConfigRepository } from "@/repositories/config";
import { requestJson, requestOk } from "./apiClient";

// ApiServer.java's handleListConfig returns only {key, value, encrypted} per entry -- tenantId is
// implicit in the URL, not repeated in the body -- but ConfigEntry.tenantId is non-optional, so it
// must be stitched back in here rather than left undefined for callers that read it off a fetched item.
type RawConfigEntry = Omit<ConfigEntry, "tenantId">;

/** Deliberately uncached (see the M2 plan's decision 4): config lists are small and this is the
 * most frequently-mutated screen, so always fetching fresh avoids a whole class of
 * "did my edit invalidate the cache" bugs for negligible cost. */
export class HttpConfigRepository implements ConfigRepository {
  async fetchPage({
    tenantId,
    cursor,
    pageSize,
  }: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<ConfigEntry>> {
    const raw = await requestJson<RawConfigEntry[]>(
      "GET",
      `/config/${encodeURIComponent(tenantId)}`,
    );
    const all: ConfigEntry[] = raw.map((e) => ({ ...e, tenantId }));
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  async upsert(entry: ConfigEntry): Promise<ConfigEntry> {
    await requestOk(
      "PUT",
      `/config/${encodeURIComponent(entry.tenantId)}/${encodeURIComponent(entry.key)}`,
      { value: entry.value, encrypted: entry.encrypted },
    );
    return entry;
  }

  async remove(tenantId: string, key: string): Promise<void> {
    await requestOk(
      "DELETE",
      `/config/${encodeURIComponent(tenantId)}/${encodeURIComponent(key)}`,
    );
  }
}
