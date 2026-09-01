import { describe, expect, it } from "vitest";
import { MockLimitRangesRepository } from "./limitranges";

describe("MockLimitRangesRepository", () => {
  const repo = new MockLimitRangesRepository();

  it("lists seeded limit ranges", async () => {
    const ranges = await repo.fetchAll();
    expect(ranges.map((r) => r.tenantId)).toContain("acme");
  });

  it("fetches one and throws for unknown", async () => {
    const range = await repo.fetchOne("acme");
    expect(range.minRequest).toEqual({ memory: "64Mi", cpu: "50m" });
    await expect(repo.fetchOne("nope")).rejects.toThrow("Limit range not found: nope");
  });

  it("leaves an undeclared bound absent rather than zeroed", async () => {
    const range = await repo.fetchOne("beta");
    expect(range.maxRequest).toEqual({ memory: "512Mi", cpu: "500m" });
    expect(range.minRequest).toBeUndefined();
    expect(range.minLimit).toBeUndefined();
    expect(range.maxLimit).toBeUndefined();
  });

  it("creates, replaces and deletes", async () => {
    await repo.save({ tenantId: "tmp", maxLimit: { memory: "1Gi", cpu: "1000m" } });
    expect((await repo.fetchOne("tmp")).maxLimit?.memory).toBe("1Gi");
    await repo.save({ tenantId: "tmp", maxLimit: { memory: "2Gi", cpu: "1000m" } });
    expect((await repo.fetchOne("tmp")).maxLimit?.memory).toBe("2Gi");
    await repo.remove("tmp");
    await expect(repo.fetchOne("tmp")).rejects.toThrow();
  });
});
