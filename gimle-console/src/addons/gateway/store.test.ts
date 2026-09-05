import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  configRepo: { fetchPage: vi.fn() },
  ingressesRepo: { fetchAll: vi.fn() },
  daemonSetsRepo: { fetchPage: vi.fn() },
  servicesRepo: { fetchAll: vi.fn(), fetchEndpoints: vi.fn() },
  endpointsRepo: { fetch: vi.fn() },
}));

import {
  configRepo,
  daemonSetsRepo,
  endpointsRepo,
  ingressesRepo,
  servicesRepo,
} from "@/repositories";
import { ApiError } from "@/repositories/http/apiClient";
import { GATEWAY_PORT_KEY, useGatewayStore } from "./store";
import type { IngressRouteJson } from "./routes-config";
import type { ConfigEntry, DaemonSet } from "@/types";

function configEntry(key: string, value: string): ConfigEntry {
  return { tenantId: "gimle-system", key, value, encrypted: false };
}

function daemonSet(ready: boolean, name = "gimle-gateway"): DaemonSet {
  return {
    spec: {
      name,
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

function seed(routes: IngressRouteJson[]) {
  vi.mocked(configRepo.fetchPage).mockResolvedValue({
    items: [configEntry(GATEWAY_PORT_KEY, "8090")],
    nextCursor: null,
  });
  vi.mocked(ingressesRepo.fetchAll).mockResolvedValue([
    { name: "edge", tenantId: "gimle-system", version: 0, routes },
  ]);
  vi.mocked(daemonSetsRepo.fetchPage).mockResolvedValue({
    items: [daemonSet(true)],
    nextCursor: null,
  });
  vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
    { name: "orders-api", tenantId: "acme", deploymentNames: ["orders"], port: 8080 },
    { name: "reports-api", tenantId: "acme", deploymentNames: ["reports"], port: 8080 },
  ]);
}

describe("useGatewayStore", () => {
  beforeEach(() => {
    useGatewayStore.setState({
      rows: [],
      routesConfigured: null,
      listenPort: null,
      instances: [],
      deployed: false,
      daemonSetName: null,
      loading: false,
      loaded: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("resolves a SERVICE route against its live endpoint set", async () => {
    seed([{ kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" }]);
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
    seed([{ kind: "SERVICE", path: "/api/nowhere", serviceName: "ghost-api" }]);

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution).toEqual({
      status: "missing",
      detail: "no such Service",
    });
    expect(servicesRepo.fetchEndpoints).not.toHaveBeenCalled();
  });

  it("separates a Service with no live endpoint from a missing one", async () => {
    seed([{ kind: "SERVICE", path: "/api/reports", serviceName: "reports-api" }]);
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "reports-api",
      port: 8080,
      endpoints: [],
    });

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution.status).toBe("empty");
  });

  it("counts only VESSEL endpoints reporting the route's own named port", async () => {
    seed([
      { kind: "VESSEL", path: "/static", deploymentName: "assets-vessel", portName: "HTTP_PORT" },
    ]);
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
    seed([
      {
        kind: "FABRIC",
        path: "/greet",
        interfaceName: "com.gimle.examples.greeter.Greeter",
        majorVersion: 1,
        methodName: "greet",
        paramType: "STRING",
      },
    ]);

    await useGatewayStore.getState().load();

    expect(useGatewayStore.getState().rows[0].resolution.status).toBe("unresolvable");
  });

  it("keeps the rest of the table when one target's read fails", async () => {
    seed([
      { kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" },
      { kind: "SERVICE", path: "/api/reports", serviceName: "reports-api" },
    ]);
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

  it("reports an undeployed gateway as a state, not an error", async () => {
    seed([{ kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" }]);
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [],
    });
    vi.mocked(daemonSetsRepo.fetchPage).mockResolvedValue({ items: [], nextCursor: null });

    await useGatewayStore.getState().load();

    const { deployed, instances, error, rows } = useGatewayStore.getState();
    expect(deployed).toBe(false);
    expect(instances).toEqual([]);
    expect(error).toBeNull();
    expect(rows).toHaveLength(1);
  });

  it("reports a tenant with no declared Ingress rather than an empty table", async () => {
    vi.mocked(configRepo.fetchPage).mockResolvedValue({ items: [], nextCursor: null });
    vi.mocked(ingressesRepo.fetchAll).mockResolvedValue([]);
    vi.mocked(daemonSetsRepo.fetchPage).mockResolvedValue({
      items: [daemonSet(true)],
      nextCursor: null,
    });
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
    seed([{ kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" }]);
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

  it("resolves one shared read for two routes naming the same target", async () => {
    seed([
      { kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" },
      { kind: "SERVICE", path: "/api/orders", prefix: true, serviceName: "orders-api" },
    ]);
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [{ host: "10.0.1.4", port: 8080 }],
    });

    await useGatewayStore.getState().load();

    const { rows } = useGatewayStore.getState();
    expect(rows).toHaveLength(2);
    expect(rows[0].resolution).toEqual({ status: "live", endpointCount: 1 });
    expect(rows[1].resolution).toEqual({ status: "live", endpointCount: 1 });
    // Both rows share one read rather than issuing the same request twice every poll.
    expect(servicesRepo.fetchEndpoints).toHaveBeenCalledTimes(1);
  });

  it("finds a gateway DaemonSet whatever it is named, by the module it runs", async () => {
    seed([{ kind: "SERVICE", path: "/api/orders", serviceName: "orders-api" }]);
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "orders-api",
      port: 8080,
      endpoints: [{ host: "10.0.0.1", port: 8080 }],
    });
    vi.mocked(daemonSetsRepo.fetchPage).mockResolvedValue({
      items: [daemonSet(true, "edge-ingress")],
      nextCursor: null,
    });

    await useGatewayStore.getState().load();

    const { deployed, daemonSetName, instances } = useGatewayStore.getState();
    expect(deployed).toBe(true);
    expect(daemonSetName).toBe("edge-ingress");
    expect(instances).toHaveLength(1);
  });
});
