import type { Ingress } from "@/addons/gateway/routes-config";
import { requestJson } from "./apiClient";

/**
 * Reads the `Ingress` resources declared for a tenant -- the gateway's route table, held by the
 * control plane as a typed resource rather than as an opaque config string.
 */
export class HttpIngressesRepository {
  async fetchAll(tenantId: string): Promise<Ingress[]> {
    const all = await requestJson<Ingress[]>("GET", "/ingresses");
    return all.filter((ingress) => ingress.tenantId === tenantId);
  }
}
