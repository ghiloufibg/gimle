import { describe, expect, it } from "vitest";
import { MockServicesRepository } from "./services";

describe("MockServicesRepository", () => {
  const repo = new MockServicesRepository();

  it("lists seeded services", async () => {
    const services = await repo.fetchAll();
    expect(services.map((s) => s.name)).toContain("orders-web");
  });

  it("fetches one and throws for unknown", async () => {
    const s = await repo.fetchOne("orders-web");
    expect(s.deploymentNames).toEqual(["orders-service"]);
    await expect(repo.fetchOne("nope")).rejects.toThrow("Service not found: nope");
  });

  it("fetches endpoints for a service with none live", async () => {
    const ep = await repo.fetchEndpoints("billing-api");
    expect(ep.endpoints).toEqual([]);
    expect(ep.port).toBe(9090);
  });

  it("creates, replaces and deletes", async () => {
    await repo.save({
      name: "tmp-svc",
      deploymentNames: ["tmp-deployment"],
      port: 1234,
      targetPort: 1234,
    });
    expect((await repo.fetchOne("tmp-svc")).port).toBe(1234);
    await repo.save({
      name: "tmp-svc",
      deploymentNames: ["tmp-deployment"],
      port: 1234,
      targetPort: 5678,
    });
    expect((await repo.fetchOne("tmp-svc")).targetPort).toBe(5678);
    await repo.remove("tmp-svc");
    await expect(repo.fetchOne("tmp-svc")).rejects.toThrow();
  });
});
