import type { WorkloadEndpoint } from "@/types";
import { delay } from "./util";

/**
 * `GET /endpoints/{name}` -- the live placement of a named workload's instances, the same read a
 * gateway instance makes to resolve a VESSEL route's target. Distinct from
 * `ServicesRepository.fetchEndpoints`, which resolves a declared Service's own endpoint set: this
 * one is keyed by workload name and carries every port that instance reported, under the
 * `vessel.env` variable name it was declared as.
 */
export interface EndpointsRepository {
  fetch(name: string, tenantId?: string | null): Promise<WorkloadEndpoint[]>;
}

const mockEndpoints: Record<string, WorkloadEndpoint[]> = {
  "orders-service": [
    { instanceIndex: 0, nodeId: "node-a", host: "10.0.1.4", ports: { HTTP_PORT: 8080 } },
    { instanceIndex: 1, nodeId: "node-b", host: "10.0.1.9", ports: { HTTP_PORT: 8080 } },
  ],
  // Placed but not yet heartbeating: no host, no ports -- the shape a route resolving to nothing
  // yet actually has on the wire.
  "billing-primary": [{ instanceIndex: 0, nodeId: "node-a", host: null, ports: {} }],
};

export class MockEndpointsRepository implements EndpointsRepository {
  async fetch(name: string, _tenantId?: string | null): Promise<WorkloadEndpoint[]> {
    return delay(mockEndpoints[name] ?? []);
  }
}
