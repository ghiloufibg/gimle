import type { Page, Tenant } from "@/types";
import { removeTenant, tenants, updateTenant } from "./fixture";
import { delay, paginate } from "./util";

export interface TenantsSummary {
  total: number;
}

export interface TenantsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<Tenant>>;
  fetchOne(id: string): Promise<Tenant>;
  fetchSummary(): Promise<TenantsSummary>;
  updateQuota(id: string, quota: Tenant["quota"]): Promise<Tenant>;
  remove(id: string): Promise<void>;
}

export class MockTenantsRepository implements TenantsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(tenants, cursor, pageSize));
  }
  async fetchOne(id: string) {
    const t = tenants.find((x) => x.id === id);
    if (!t) throw new Error(`Tenant not found: ${id}`);
    return delay(t);
  }
  async fetchSummary() {
    return delay<TenantsSummary>({ total: tenants.length });
  }
  async updateQuota(id: string, quota: Tenant["quota"]) {
    updateTenant(id, quota);
    const t = tenants.find((x) => x.id === id)!;
    return delay(t);
  }
  async remove(id: string) {
    removeTenant(id);
    return delay(undefined);
  }
}
