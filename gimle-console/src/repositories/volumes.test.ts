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
});
