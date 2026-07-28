import type { ConfigEntry, Page } from "@/types";
import { configByTenant, removeConfig, upsertConfig } from "./fixture";
import { delay, paginate } from "./util";

export interface ConfigRepository {
  fetchPage(args: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }): Promise<Page<ConfigEntry>>;
  upsert(entry: ConfigEntry): Promise<ConfigEntry>;
  remove(tenantId: string, key: string): Promise<void>;
}

export class MockConfigRepository implements ConfigRepository {
  async fetchPage({
    tenantId,
    cursor,
    pageSize,
  }: {
    tenantId: string;
    cursor: string | null;
    pageSize: number;
  }) {
    const list = configByTenant[tenantId] ?? [];
    return delay(paginate(list, cursor, pageSize));
  }
  async upsert(entry: ConfigEntry) {
    upsertConfig(entry);
    return delay(entry);
  }
  async remove(tenantId: string, key: string) {
    removeConfig(tenantId, key);
    return delay(undefined);
  }
}
