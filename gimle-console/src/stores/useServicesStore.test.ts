import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  servicesRepo: {
    fetchAll: vi.fn(),
    fetchOne: vi.fn(),
    fetchEndpoints: vi.fn(),
    save: vi.fn(),
    remove: vi.fn(),
  },
}));

import { servicesRepo } from "@/repositories";
import type { Service } from "@/types";
import { useServicesStore } from "./useServicesStore";

function service(name: string): Service {
  return { name, deploymentNames: ["d"], port: 80 };
}

describe("useServicesStore.save", () => {
  beforeEach(() => {
    useServicesStore.setState({ items: [], loading: false, loaded: false, error: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  // Regression: the control plane's own overlap/unreported-target-port advisory (a
  // X-Gimle-Warning header on an otherwise-successful save) was previously dropped between the
  // repository and the form -- the console showed only a bare "saved" toast, unlike gimle-cli,
  // which prints the identical warning. The store must hand it back to the caller unchanged.
  it("returns the repository's warning to the caller instead of dropping it", async () => {
    vi.mocked(servicesRepo.save).mockResolvedValueOnce(
      "service orders-web fronts deployment(s) [d] already fronted by service orders-alias",
    );
    vi.mocked(servicesRepo.fetchAll).mockResolvedValueOnce([service("orders-web")]);

    const warning = await useServicesStore.getState().save(service("orders-web"));

    expect(warning).toContain("already fronted by service orders-alias");
    expect(useServicesStore.getState().items).toEqual([service("orders-web")]);
  });

  it("returns null when the save carried no warning", async () => {
    vi.mocked(servicesRepo.save).mockResolvedValueOnce(null);
    vi.mocked(servicesRepo.fetchAll).mockResolvedValueOnce([service("orders-web")]);

    const warning = await useServicesStore.getState().save(service("orders-web"));

    expect(warning).toBeNull();
  });

  it("rejects with the repository's error and does not swallow it into store.error", async () => {
    vi.mocked(servicesRepo.save).mockRejectedValueOnce(new Error("port must be in [1, 65535]"));

    await expect(useServicesStore.getState().save(service("orders-web"))).rejects.toThrow(
      "port must be in [1, 65535]",
    );
    expect(useServicesStore.getState().error).toBe("port must be in [1, 65535]");
  });
});
