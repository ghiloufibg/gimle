import { describe, expect, it } from "vitest";
import { checkRetireTarget, retirementConfirmed } from "./key-retirement";

const ACTIVE = 4;
const NOUN = "secrets master key";

describe("checkRetireTarget", () => {
  it("accepts an in-range id that is neither the base nor the active key", () => {
    expect(checkRetireTarget("2", ACTIVE, NOUN)).toEqual({ keyId: 2, error: null });
    expect(checkRetireTarget("  2  ", ACTIVE, NOUN)).toEqual({ keyId: 2, error: null });
    expect(checkRetireTarget("255", ACTIVE, NOUN)).toEqual({ keyId: 255, error: null });
  });

  it("refuses an empty or non-numeric id", () => {
    expect(checkRetireTarget("", ACTIVE, NOUN).keyId).toBeNull();
    expect(checkRetireTarget("   ", ACTIVE, NOUN).error).toMatch(/Enter the id/);
    expect(checkRetireTarget("2a", ACTIVE, NOUN).error).toMatch(/whole number/);
    expect(checkRetireTarget("-1", ACTIVE, NOUN).error).toMatch(/whole number/);
    expect(checkRetireTarget("1.5", ACTIVE, NOUN).error).toMatch(/whole number/);
  });

  it("refuses an id past the single unsigned byte the wire carries", () => {
    const check = checkRetireTarget("256", ACTIVE, NOUN);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/between 0 and 255/);
  });

  it("refuses the base key, which regenerates rather than staying retired", () => {
    const check = checkRetireTarget("0", ACTIVE, NOUN);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/base secrets master key/);
  });

  it("refuses the active key and says to rotate first", () => {
    const check = checkRetireTarget(String(ACTIVE), ACTIVE, NOUN);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/Rotate first/);
  });

  it("lets an id through for the server to rule on while no active key id is known", () => {
    expect(checkRetireTarget(String(ACTIVE), null, NOUN)).toEqual({ keyId: ACTIVE, error: null });
  });

  it("names the ring it was asked about in every rejection", () => {
    expect(checkRetireTarget("", null, "sealing key").error).toContain("sealing key");
    expect(checkRetireTarget("x", null, "sealing key").error).toContain("sealing key");
    expect(checkRetireTarget("0", null, "sealing key").error).toContain("sealing key");
  });
});

describe("retirementConfirmed", () => {
  it("requires the exact id typed back, ignoring surrounding whitespace", () => {
    expect(retirementConfirmed("2", 2)).toBe(true);
    expect(retirementConfirmed(" 2 ", 2)).toBe(true);
  });

  it("rejects an empty, partial, or mismatched confirmation", () => {
    expect(retirementConfirmed("", 2)).toBe(false);
    expect(retirementConfirmed("3", 2)).toBe(false);
    expect(retirementConfirmed("12", 2)).toBe(false);
    expect(retirementConfirmed("02", 2)).toBe(false);
  });
});
