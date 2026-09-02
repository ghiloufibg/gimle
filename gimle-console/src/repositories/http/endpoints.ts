import type { WorkloadEndpoint } from "@/types";
import type { EndpointsRepository } from "@/repositories/endpoints";
import { requestJson, tenantQuery } from "./apiClient";

// Wire shape -- mirrors ApiServer.java's endpointEntry(). `host` is absent until the node this
// instance was placed on has registered an API address, and `ports` until that instance has
// heartbeated an observation, so both are optional here and normalized below rather than left
// undefined for callers.
interface RawWorkloadEndpoint {
  instanceIndex: number;
  nodeId: string;
  host?: string;
  ports?: Record<string, number>;
}

export class HttpEndpointsRepository implements EndpointsRepository {
  async fetch(name: string, tenantId?: string | null): Promise<WorkloadEndpoint[]> {
    const raw = await requestJson<RawWorkloadEndpoint[]>(
      "GET",
      `/endpoints/${encodeURIComponent(name)}${tenantQuery(tenantId)}`,
    );
    return raw.map((e) => ({
      instanceIndex: e.instanceIndex,
      nodeId: e.nodeId,
      host: e.host ?? null,
      ports: e.ports ?? {},
    }));
  }
}
