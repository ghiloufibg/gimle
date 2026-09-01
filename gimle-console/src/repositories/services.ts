import type { Service, ServiceEndpoints } from "@/types";
import { delay } from "./util";

export interface ServicesRepository {
  fetchAll(): Promise<Service[]>;
  fetchOne(name: string): Promise<Service>;
  fetchEndpoints(name: string, tenantId?: string | null): Promise<ServiceEndpoints>;
  save(spec: Service): Promise<void>;
  remove(name: string, tenantId?: string | null): Promise<void>;
}

const mockServices: Service[] = [
  {
    name: "orders-web",
    tenantId: "acme",
    deploymentNames: ["orders-service"],
    port: 8080,
    targetPort: 8080,
  },
  {
    name: "billing-api",
    tenantId: "acme",
    deploymentNames: ["billing-primary", "billing-canary"],
    port: 9090,
  },
];

const mockEndpoints: Record<string, { host: string; port: number }[]> = {
  "orders-web": [
    { host: "10.0.1.4", port: 8080 },
    { host: "10.0.1.9", port: 8080 },
  ],
  "billing-api": [],
};

export class MockServicesRepository implements ServicesRepository {
  async fetchAll(): Promise<Service[]> {
    return delay(mockServices.map((s) => ({ ...s, deploymentNames: [...s.deploymentNames] })));
  }

  async fetchOne(name: string): Promise<Service> {
    const s = mockServices.find((x) => x.name === name);
    if (!s) throw new Error(`Service not found: ${name}`);
    return delay({ ...s, deploymentNames: [...s.deploymentNames] });
  }

  async fetchEndpoints(name: string, _tenantId?: string | null): Promise<ServiceEndpoints> {
    const s = mockServices.find((x) => x.name === name);
    if (!s) throw new Error(`Service not found: ${name}`);
    return delay({
      name: s.name,
      port: s.port,
      targetPort: s.targetPort,
      endpoints: mockEndpoints[name] ?? [],
    });
  }

  async save(spec: Service): Promise<void> {
    const i = mockServices.findIndex((x) => x.name === spec.name);
    if (i >= 0) mockServices[i] = spec;
    else mockServices.push(spec);
    return delay(undefined);
  }

  async remove(name: string, _tenantId?: string | null): Promise<void> {
    const i = mockServices.findIndex((x) => x.name === name);
    if (i >= 0) mockServices.splice(i, 1);
    delete mockEndpoints[name];
    return delay(undefined);
  }
}
