import type { Page, Tenant } from "@/types";
import type { TenantsRepository, TenantsSummary } from "@/repositories/tenants";
import { requestJson, requestOk } from "./apiClient";

export class HttpTenantsRepository implements TenantsRepository {
  private cache: Tenant[] | null = null;

  private async all(forceRefresh: boolean): Promise<Tenant[]> {
    if (forceRefresh || this.cache === null) {
      this.cache = await requestJson<Tenant[]>("GET", "/tenants");
    }
    return this.cache;
  }

  async fetchPage({
    cursor,
    pageSize,
  }: {
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<Tenant>> {
    const all = await this.all(cursor === null);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, all.length);
    return { items: all.slice(start, end), nextCursor: end < all.length ? String(end) : null };
  }

  async fetchOne(id: string): Promise<Tenant> {
    const all = await this.all(false);
    const t = all.find((x) => x.id === id);
    if (t) return t;
    const fresh = await this.all(true);
    const found = fresh.find((x) => x.id === id);
    if (!found) throw new Error(`Tenant not found: ${id}`);
    return found;
  }

  async fetchSummary(): Promise<TenantsSummary> {
    const all = await this.all(false);
    return { total: all.length };
  }

  async updateQuota(id: string, quota: Tenant["quota"]): Promise<Tenant> {
    await requestOk("PUT", `/tenants/${encodeURIComponent(id)}`, { quota });
    this.cache = null;
    // Don't depend on parsing the PUT response body (its exact shape isn't pinned down): a
    // successful (2xx) PUT means the control plane accepted exactly this quota, so construct the
    // result client-side rather than a speculative follow-up GET.
    return { id, quota };
  }

  async remove(id: string): Promise<void> {
    await requestOk("DELETE", `/tenants/${encodeURIComponent(id)}`);
    this.cache = null;
  }
}
