import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  servicesRepo: { fetchAll: vi.fn(), fetchEndpoints: vi.fn() },
  metricsHistoryRepo: { fetchPage: vi.fn() },
}));

import { metricsHistoryRepo, servicesRepo } from "@/repositories";
import { ApiError } from "@/repositories/http/apiClient";
import { SKALD_FAILURES_METRIC, SKALD_STALENESS_METRIC } from "@/lib/skald-dns";
import { useSkaldStore } from "./useSkaldStore";
import type { MetricsHistoryLine } from "@/types";

function gauge(name: string, value: number, timestamp: string): MetricsHistoryLine {
  return { timestamp, name, type: "GAUGE", tags: {}, measurements: { VALUE: value } };
}

describe("useSkaldStore", () => {
  beforeEach(() => {
    useSkaldStore.setState({
      names: [],
      responders: [],
      loading: false,
      loaded: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("derives a zone name per Service and counts its A records", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
      { name: "orders-api", tenantId: "acme", deploymentNames: ["orders"], port: 8080 },
      { name: "greeter", tenantId: undefined, deploymentNames: ["greeter-provider"], port: 7070 },
    ]);
    vi.mocked(servicesRepo.fetchEndpoints).mockImplementation(async (name: string) => ({
      name,
      port: 8080,
      endpoints:
        name === "orders-api"
          ? [
              { host: "10.0.1.4", port: 8080 },
              { host: "10.0.1.9", port: 8080 },
            ]
          : [{ host: "10.0.2.2", port: 7070 }],
    }));

    await useSkaldStore.getState().load();

    const { names } = useSkaldStore.getState();
    expect(names.map((n) => n.dnsName)).toEqual([
      "orders-api.acme.svc.gimle.local",
      "greeter.svc.gimle.local",
    ]);
    expect(names[0].addressCount).toBe(2);
    expect(names[1].addressCount).toBe(1);
    expect(names[0].port).toBe(8080);
    expect(names[1].tenantId).toBeNull();
  });

  it("marks a name with no live endpoint rather than hiding it", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
      { name: "reports", tenantId: "acme", deploymentNames: ["reports"], port: 8080 },
    ]);
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "reports",
      port: 8080,
      endpoints: [],
    });

    await useSkaldStore.getState().load();

    expect(useSkaldStore.getState().names[0]).toMatchObject({ addressCount: 0, port: null });
  });

  it("separates an unreadable endpoint set from an empty one", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
      { name: "reports", tenantId: "acme", deploymentNames: ["reports"], port: 8080 },
    ]);
    vi.mocked(servicesRepo.fetchEndpoints).mockRejectedValue(new ApiError(500, "boom"));

    await useSkaldStore.getState().load();

    const [name] = useSkaldStore.getState().names;
    expect(name.addressCount).toBe(0);
    expect(name.unreadable).toContain("500");
  });

  it("reads a tracked responder's newest staleness and failure gauges", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([]);
    vi.mocked(metricsHistoryRepo.fetchPage).mockResolvedValue({
      lines: [
        gauge(SKALD_STALENESS_METRIC, 4, "2026-09-02T10:00:00Z"),
        gauge(SKALD_STALENESS_METRIC, 31, "2026-09-02T10:00:30Z"),
        gauge(SKALD_FAILURES_METRIC, 4, "2026-09-02T10:00:30Z"),
      ],
      olderCursor: null,
      newerCursor: null,
    });

    await useSkaldStore.getState().addResponder("skald-2:8053");

    const [responder] = useSkaldStore.getState().responders;
    expect(responder).toMatchObject({
      address: "skald-2:8053",
      stalenessSeconds: 31,
      consecutiveFailures: 4,
      error: null,
    });
    expect(metricsHistoryRepo.fetchPage).toHaveBeenCalledWith(
      expect.objectContaining({ target: { processKind: "SKALD", processId: "skald-2:8053" } }),
    );
  });

  it("reports a responder that has shipped nothing without failing the screen", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([]);
    vi.mocked(metricsHistoryRepo.fetchPage).mockResolvedValue({
      lines: [],
      olderCursor: null,
      newerCursor: null,
    });

    await useSkaldStore.getState().addResponder("skald-1:8053");

    expect(useSkaldStore.getState().responders[0]).toMatchObject({
      stalenessSeconds: null,
      lastReadingAt: null,
      error: null,
    });
  });

  it("keeps a failed responder read on its own row instead of erroring the screen", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([]);
    vi.mocked(metricsHistoryRepo.fetchPage).mockRejectedValue(new ApiError(404, "no history"));

    await useSkaldStore.getState().addResponder("skald-3:8053");
    await useSkaldStore.getState().load();

    const { responders, error } = useSkaldStore.getState();
    expect(responders[0].error).toContain("404");
    expect(error).toBeNull();
  });

  it("ignores a blank or already-tracked responder address", async () => {
    vi.mocked(metricsHistoryRepo.fetchPage).mockResolvedValue({
      lines: [],
      olderCursor: null,
      newerCursor: null,
    });

    await useSkaldStore.getState().addResponder("  ");
    await useSkaldStore.getState().addResponder("skald-1:8053");
    await useSkaldStore.getState().addResponder("skald-1:8053");

    expect(useSkaldStore.getState().responders).toHaveLength(1);
  });

  it("surfaces a failed service list as the screen's error", async () => {
    vi.mocked(servicesRepo.fetchAll).mockRejectedValue(new ApiError(403, "forbidden"));

    await useSkaldStore.getState().load();

    expect(useSkaldStore.getState().error).toContain("permission");
    expect(useSkaldStore.getState().loaded).toBe(false);
  });

  it("counts one A record per distinct host, not per endpoint", async () => {
    vi.mocked(servicesRepo.fetchAll).mockResolvedValue([
      { name: "packed", tenantId: "acme", deploymentNames: ["packed-deployment"], port: 8080 },
    ]);
    // Two replicas placed on one node: one address, so one A record -- what a resolver actually
    // gets back, which is not the same as the endpoint count.
    vi.mocked(servicesRepo.fetchEndpoints).mockResolvedValue({
      name: "packed",
      port: 8080,
      endpoints: [
        { host: "10.0.1.4", port: 18080 },
        { host: "10.0.1.4", port: 18081 },
      ],
    });

    await useSkaldStore.getState().load();

    expect(useSkaldStore.getState().names[0].addressCount).toBe(1);
  });
});
