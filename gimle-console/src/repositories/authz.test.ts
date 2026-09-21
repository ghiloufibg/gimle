import { describe, expect, it } from "vitest";
import { MockCanIRepository } from "./authz";

describe("MockCanIRepository", () => {
  const repo = new MockCanIRepository();

  it("allows everything, matching the real endpoint's own plaintext-mode carve-out", async () => {
    expect(await repo.check("TENANT", "DELETE")).toBe(true);
    expect(await repo.check("SECRET", "WRITE", "acme")).toBe(true);
  });
});
