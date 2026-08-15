import { describe, expect, it } from "vitest";

import { sha256Hex, verifySha256 } from "./hash";

describe("sha256Hex", () => {
  it('matches the standard NIST test vector for "abc"', async () => {
    const bytes = new TextEncoder().encode("abc").buffer;

    const digest = await sha256Hex(bytes);

    expect(digest).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});

describe("verifySha256", () => {
  it("accepts the correct digest", async () => {
    const bytes = new TextEncoder().encode("pretend jar bytes").buffer;
    const expected = await sha256Hex(bytes);

    await expect(verifySha256(bytes, expected)).resolves.toBe(true);
  });

  it("is case-insensitive", async () => {
    const bytes = new TextEncoder().encode("pretend jar bytes").buffer;
    const expected = await sha256Hex(bytes);

    await expect(verifySha256(bytes, expected.toUpperCase())).resolves.toBe(true);
  });

  it("rejects a mismatched digest", async () => {
    const bytes = new TextEncoder().encode("pretend jar bytes").buffer;

    await expect(verifySha256(bytes, "0".repeat(64))).resolves.toBe(false);
  });

  it("rejects when the bytes were tampered with after the digest was reported", async () => {
    const original = new TextEncoder().encode("original jar bytes").buffer;
    const expected = await sha256Hex(original);
    const tampered = new TextEncoder().encode("tampered jar bytes").buffer;

    await expect(verifySha256(tampered, expected)).resolves.toBe(false);
  });
});
