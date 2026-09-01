import { describe, expect, it } from "vitest";
import { MockSealRepository } from "./seal";

describe("MockSealRepository", () => {
  it("returns the active sealing key with its algorithm", async () => {
    const repo = new MockSealRepository();

    const key = await repo.fetchPublicKey();

    expect(key.sealingKeyId).toBe(3);
    expect(key.algorithm).toBe("RSA-OAEP-SHA256");
    expect(key.publicKey.length).toBeGreaterThan(0);
  });

  it("rotation mints the next id and makes it the active one", async () => {
    const repo = new MockSealRepository();

    const rotated = await repo.rotateKey();

    expect(rotated.activeSealingKeyId).toBe(4);
    expect((await repo.fetchPublicKey()).sealingKeyId).toBe(4);
  });

  it("retires an older key and echoes back the id it acted on", async () => {
    const repo = new MockSealRepository();

    expect(await repo.retireKey(1)).toEqual({ retiredKeyId: 1 });
  });

  it("refuses the active key and an out-of-byte-range id", async () => {
    const repo = new MockSealRepository();

    await expect(repo.retireKey(3)).rejects.toThrow("cannot retire the active sealing key");
    await expect(repo.retireKey(999)).rejects.toThrow("between 0 and 255");
  });
});
