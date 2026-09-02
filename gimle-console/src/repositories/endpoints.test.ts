import { describe, expect, it } from "vitest";
import { MockEndpointsRepository } from "./endpoints";

describe("MockEndpointsRepository", () => {
  const repo = new MockEndpointsRepository();

  it("lists a placed workload's live instances", async () => {
    const endpoints = await repo.fetch("orders-service");
    expect(endpoints.map((e) => e.host)).toEqual(["10.0.1.4", "10.0.1.9"]);
    expect(endpoints[0].ports).toEqual({ HTTP_PORT: 8080 });
  });

  it("reports a placed but unheartbeated instance with no host or port", async () => {
    const [endpoint] = await repo.fetch("billing-primary");
    expect(endpoint.host).toBeNull();
    expect(endpoint.ports).toEqual({});
  });

  it("returns nothing for an unknown workload rather than throwing", async () => {
    expect(await repo.fetch("nope")).toEqual([]);
  });
});
