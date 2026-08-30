import type { CustomResourceItem, KindDefinitionSummary } from "@/types";
import type { CustomResourcesRepository } from "@/repositories/customResources";
import { requestJson } from "./apiClient";

/**
 * Reads the control plane's own custom-kind surfaces verbatim: `/kinddefinitions` for the catalog
 * and `/resources/{kind}` for one kind's instances -- the exact routes the CLI's `gimle kinds`/
 * `gimle get <kind>` already consume, RBAC-filtered server-side per the session principal (a
 * caller sees only tenants its READ grants cover; rows never need client-side filtering).
 */
export class HttpCustomResourcesRepository implements CustomResourcesRepository {
  async fetchKinds(): Promise<KindDefinitionSummary[]> {
    return requestJson<KindDefinitionSummary[]>("GET", "/kinddefinitions");
  }

  async fetchResources(kindName: string): Promise<CustomResourceItem[]> {
    return requestJson<CustomResourceItem[]>("GET", `/resources/${encodeURIComponent(kindName)}`);
  }
}
