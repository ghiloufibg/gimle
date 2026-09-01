import { describe, expect, it } from "vitest";
import { MockVolumesRepository } from "./volumes";

describe("MockVolumesRepository", () => {
  const repo = new MockVolumesRepository();

  it("lists volumes alongside the nodes it could not reach", async () => {
    const listing = await repo.fetchAll();
    expect(listing.volumes.map((v) => v.statefulSet)).toContain("orders-store");
    expect(listing.unreachableNodes).toEqual(["node-c"]);
  });

  it("reports an untenanted volume as an explicit null, not an absent field", async () => {
    const listing = await repo.fetchAll();
    const orphan = listing.volumes.find((v) => v.statefulSet === "ledger");
    expect(orphan?.tenantId).toBeNull();
  });

  it("hands out a fresh copy each call, so a screen cannot mutate the fixture in place", async () => {
    const first = await repo.fetchAll();
    first.volumes[0].usedBytes = -1;
    first.unreachableNodes?.push("node-z");

    const second = await repo.fetchAll();

    expect(second.volumes[0].usedBytes).toBeGreaterThan(0);
    expect(second.unreachableNodes).toEqual(["node-c"]);
  });

  it("reports attached and inUse independently rather than deriving one from the other", async () => {
    const listing = await repo.fetchAll();
    const live = listing.volumes.find((v) => v.statefulSet === "orders-store");
    expect(live).toMatchObject({ attached: true, inUse: true });
  });

  it("refuses to destroy a still-attached volume", async () => {
    await expect(repo.destroy("node-a", "orders-store", 0, "acme")).rejects.toThrow(
      "still attached",
    );
  });

  it("destroys a retained orphan and throws for an unknown one", async () => {
    await repo.destroy("node-b", "ledger", 3);
    const listing = await repo.fetchAll();
    expect(listing.volumes.map((v) => v.statefulSet)).not.toContain("ledger");
    await expect(repo.destroy("node-b", "ledger", 3)).rejects.toThrow("Volume not found");
  });

  it("leaves every other node's volumes in place after a destroy", async () => {
    const listing = await repo.fetchAll();
    expect(listing.volumes.map((v) => v.statefulSet)).toContain("orders-store");
    expect(listing.unreachableNodes).toEqual(["node-c"]);
  });
});
