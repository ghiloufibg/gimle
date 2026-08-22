import type { NetworkPolicy } from "@/types";
import { delay } from "./util";

export interface NetworkPoliciesRepository {
  fetchAll(): Promise<NetworkPolicy[]>;
  fetchOne(name: string): Promise<NetworkPolicy>;
  save(spec: NetworkPolicy): Promise<void>;
  remove(name: string): Promise<void>;
}

const mockNetworkPolicies: NetworkPolicy[] = [
  {
    name: "acme-billing-policy",
    tenantId: "acme",
    deploymentNames: ["billing-primary", "billing-canary"],
    allowedCallerTenantIds: ["partner"],
  },
];

export class MockNetworkPoliciesRepository implements NetworkPoliciesRepository {
  async fetchAll(): Promise<NetworkPolicy[]> {
    return delay(
      mockNetworkPolicies.map((p) => ({
        ...p,
        deploymentNames: [...p.deploymentNames],
        allowedCallerTenantIds: [...p.allowedCallerTenantIds],
      })),
    );
  }

  async fetchOne(name: string): Promise<NetworkPolicy> {
    const p = mockNetworkPolicies.find((x) => x.name === name);
    if (!p) throw new Error(`NetworkPolicy not found: ${name}`);
    return delay({
      ...p,
      deploymentNames: [...p.deploymentNames],
      allowedCallerTenantIds: [...p.allowedCallerTenantIds],
    });
  }

  async save(spec: NetworkPolicy): Promise<void> {
    const i = mockNetworkPolicies.findIndex((x) => x.name === spec.name);
    if (i >= 0) mockNetworkPolicies[i] = spec;
    else mockNetworkPolicies.push(spec);
    return delay(undefined);
  }

  async remove(name: string): Promise<void> {
    const i = mockNetworkPolicies.findIndex((x) => x.name === name);
    if (i >= 0) mockNetworkPolicies.splice(i, 1);
    return delay(undefined);
  }
}
