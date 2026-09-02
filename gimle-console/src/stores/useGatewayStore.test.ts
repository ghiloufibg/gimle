import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  configRepo: { fetchPage: vi.fn() },
  daemonSetsRepo: { fetchOne: vi.fn() },
  servicesRepo: { fetchAll: vi.fn(), fetchEndpoints: vi.fn() },
  endpointsRepo: { fetch: vi.fn() },
}));

import { configRepo, daemonSetsRepo, endpointsRepo, servicesRepo } from "@/repositories";
import { ApiError } from "@/repositories/http/apiClient";
import { GATEWAY_PORT_KEY, GATEWAY_ROUTES_KEY, useGatewayStore } from "./useGatewayStore";
import type { ConfigEntry, DaemonSet } from "@/types";

function configEntry(key: string, value: string): ConfigEntry {
  return { tenantId: "gimle-system", key, value, encrypted: false };
}

function daemonSet(ready: boolean): DaemonSet {
  return {
    spec: {
      name: "gimle-gateway",
      moduleId: { name: "com.gimle.gateway", version: "1.0.0" },
      artifactPath: "gimle-gateway.jar",
      placement: { requiredNodeLabels: ["edge"] },
      tenantId: "gimle-system",
    },
    instances: [
      {
        nodeId: "edge-1",
        observation: {
          lifecycleState: "ACTIVE",
          alive: true,
          ready,
          requestRatePerSecond: 1,
          errorRatePerSecond: 0,
          queueDepth: 0,
          cpuMillicoresUsed: 10,
          memoryBytesUsed: 1024,
          workerId: "worker-1",
        },
      },
    ],
  };
}

function seed(routes: string) {
  vi.mocked(configRepo.fetchPage).mockResolvedValue({
    items: [configEntry(GATEWAY_ROUTES_KEY, routes), configEntry(GATEWAY_PORT_KEY, "8090")],
    nextCursor: null,
  });
  vi.mocked(daemonSetsRepo.fetchOne).mockResolvedValue(daemonSet(true));
  vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
    { name: "orders-api", tenantId: "acme", deploymentNames: ["orders"], port: 8080 },
    { name: "reports-api", tenantId: "acme", deploymentNames: ["reports"], port: 8080 },
  ]);
}

describe("useGatewayStore", () => {
  beforeEach(() => {
    useGatewayStore.setState({
      rows: [],
      parseErrors: [],
      routesConfigured: null,
      listenPort: null,
      instances: [],
      deployed: false,
      loading: false,
      loaded: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("resolves a SERVICE route against its live endpoint set", async () => {
    seed("SERVICE /api/orders orders-api\n");
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [
        { host: "10.0.1.4", port: 8080 },
        { host: "10.0.1.9", port: 8080 },
      ],
    });

    await useGatewayStore.getState().load();

    const { rows, listenPort, routesConfigured, deployed } = useGatewayStore.getState();
    expect(rows).toHaveLength(1);
    expect(rows[0].resolution).toEqual({ status: "live", endpointCount: 2 });
    expect(listenPort).toBe("8090");
    expect(routesConfigured).toBe(true);
    expect(deployed).toBe(true);
    // The Service is tenant-scoped, so its own tenant has to ride the endpoints read.
    expect(servicesRepo.fetchEndpoints).toHaveBeenCalledWith("orders-api", "acme");
  });

  it("flags a route whose Service does not exist at all", async () => {
    seed("SERVICE /api/nowhere ghost-api\n");

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution).toEqual({
      status: "missing",
      detail: "no such Service",
    });
    expect(servicesRepo.fetchEndpoints).not.toHaveBeenCalled();
  });

  it("separates a Service with no live endpoint from a missing one", async () => {
    seed("SERVICE /api/reports reports-api\n");
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "reports-api",
      port: 8080,
      endpoints: [],
    });

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution.status).toBe("empty");
  });

  it("counts only VESSEL endpoints reporting the route's own named port", async () => {
    seed("VESSEL /static assets-vessel HTTP_PORT\n");
    vi.mocked(endpointsRepo.fetch).mockResolvedValue([
      { instanceIndex: 0, nodeId: "node-a", host: "10.0.1.4", ports: { HTTP_PORT: 8080 } },
      { instanceIndex: 1, nodeId: "node-b", host: "10.0.1.9", ports: { ADMIN_PORT: 9000 } },
      { instanceIndex: 2, nodeId: "node-c", host: null, ports: { HTTP_PORT: 8080 } },
    ]);

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution).toEqual({
      status: "live",
      endpointCount: 1,
    });
  });

  it("never claims to resolve a FABRIC route", async () => {
    seed("FABRIC /greet com.gimle.examples.greeter.Greeter 1 greet STRING\n");

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution.status).toBe("unresolvable");
  });

  it("keeps the rest of the table when one target's read fails", async () => {
    seed("SERVICE /api/orders orders-api\nSERVICE /api/reports reports-api\n");
    vi.mocked(servicesRepo.fetchEndpoints).mockImplementation(async (name: string) => {
      if (name === "orders-api") throw new ApiError(500, "boom");
      return { name, port: 8080, endpoints: [{ host: "10.0.1.4", port: 8080 }] };
    });

    await useGatewayStore.getState().load();

    const { rows, error } = useGatewayStore.getState();
    expect(rows[0].resolution.status).toBe("unknown");
    expect(rows[1].resolution).toEqual({ status: "live", endpointCount: 1 });
    expect(error).toBeNull();
  });

  it("surfaces unparseable lines instead of dropping them", async () => {
    seed("SERVICE /api/orders\n");

    await useGatewayStore.getState().load();

    const { rows, parseErrors } = useGatewayStore.getState();
    expect(rows).toEqual([]);
    expect(parseErrors[0].line).toBe(1);
  });

  it("reports an undeployed gateway as a state, not an error", async () => {
    seed("SERVICE /api/orders orders-api\n");
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [],
    });
    vi.mocked(daemonSetsRepo.fetchOne).mockRejectedValue(new ApiError(404, "not found"));

    await useGatewayStore.getState().load();

    const { deployed, instances, error, rows } = useGatewayStore.getState();
    expect(deployed).toBe(false);
    expect(instances).toEqual([]);
    expect(error).toBeNull();
    expect(rows).toHaveLength(1);
  });

  it("reports a missing gateway.routes key rather than an empty table", async () => {
    vi.mocked(configRepo.fetchPage).mockResolvedValue({ items: [], nextCursor: null });
    vi.mocked(daemonSetsRepo.fetchOne).mockResolvedValue(daemonSet(true));
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([]);

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().routesConfigured).toBe(false);
    expect(useGatewayStore.getState().listenPort).toBeNull();
  });

  it("surfaces a failed config read as the screen's error", async () => {
    vi.mocked(configRepo.fetchPage).mockRejectedValue(new ApiError(403, "forbidden"));

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().error).toContain("permission");
    expect(useGatewayStore.getState().loaded).toBe(false);
  });

  it("leaves the last good table in place when a poll fails", async () => {
    seed("SERVICE /api/orders orders-api\n");
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [{ host: "10.0.1.4", port: 8080 }],
    });
    await useGatewayStore.getState().load();

    vi.mocked(configRepo.fetchPage).mockRejectedValue(new ApiError(503, "unavailable"));
    await useGatewayStore.getState().poll();

    expect(useGatewayStore.getState().rows).toHaveLength(1);
    expect(useGatewayStore.getState().error).toContain("503");
  });
});
