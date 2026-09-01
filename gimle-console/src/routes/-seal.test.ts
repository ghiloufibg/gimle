import { describe, expect, it } from "vitest";
import { checkRetireTarget, retirementConfirmed } from "./-seal";

// Pure retire-target validation and confirmation-gate logic only -- this project's vitest config is
// deliberately node-environment (see vitest.config.ts); the JSX half of this screen is exercised
// live in a real browser instead, not here.

const ACTIVE = 4;

describe("checkRetireTarget", () => {
  it("accepts an in-range id that is neither the base nor the active key", () => {
    expect(checkRetireTarget("2", ACTIVE)).toEqual({ keyId: 2, error: null });
    expect(checkRetireTarget("  2  ", ACTIVE)).toEqual({ keyId: 2, error: null });
    expect(checkRetireTarget("255", ACTIVE)).toEqual({ keyId: 255, error: null });
  });

  it("refuses an empty or non-numeric id", () => {
    expect(checkRetireTarget("", ACTIVE).keyId).toBeNull();
    expect(checkRetireTarget("   ", ACTIVE).error).toMatch(/Enter the id/);
    expect(checkRetireTarget("2a", ACTIVE).error).toMatch(/whole number/);
    expect(checkRetireTarget("-1", ACTIVE).error).toMatch(/whole number/);
    expect(checkRetireTarget("1.5", ACTIVE).error).toMatch(/whole number/);
  });

  it("refuses an id past the single unsigned byte the wire carries", () => {
    const check = checkRetireTarget("256", ACTIVE);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/between 0 and 255/);
  });

  it("refuses the base key, which regenerates rather than staying retired", () => {
    const check = checkRetireTarget("0", ACTIVE);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/base sealing key/);
  });

  it("refuses the active key and says to rotate first", () => {
    const check = checkRetireTarget(String(ACTIVE), ACTIVE);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/Rotate first/);
  });

  it("lets an id through for the server to rule on while no active key is loaded", () => {
    expect(checkRetireTarget(String(ACTIVE), null)).toEqual({ keyId: ACTIVE, error: null });
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
