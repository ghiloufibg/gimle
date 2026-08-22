import type { NetworkPolicy } from "@/types";
import type { NetworkPoliciesRepository } from "@/repositories/networkPolicies";
import { requestJson, requestOk } from "./apiClient";

/**
 * `GET`/`POST`/`DELETE /networkpolicies*` -- flat array response, no pagination, the identical
 * shape `HttpServicesRepository` establishes for its own sibling network-model resource. `save`
 * always POSTs to the bare `/networkpolicies` collection, never `PUT /networkpolicies/{name}`, for
 * the same reason: a `NetworkPolicySpec` names itself in the request body.
 */
export class HttpNetworkPoliciesRepository implements NetworkPoliciesRepository {
  async fetchAll(): Promise<NetworkPolicy[]> {
    return requestJson<NetworkPolicy[]>("GET", "/networkpolicies");
  }

  async fetchOne(name: string): Promise<NetworkPolicy> {
    return requestJson<NetworkPolicy>("GET", `/networkpolicies/${encodeURIComponent(name)}`);
  }

  async save(spec: NetworkPolicy): Promise<void> {
    await requestOk("POST", "/networkpolicies", spec);
  }

  async remove(name: string): Promise<void> {
    await requestOk("DELETE", `/networkpolicies/${encodeURIComponent(name)}`);
  }
}
