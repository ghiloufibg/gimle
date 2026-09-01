import { describe, expect, it } from "vitest";
import { checkRetireTarget, retirementConfirmed } from "./-secrets";

// Pure retire-target validation and confirmation-gate logic only -- this project's vitest config is
// deliberately node-environment (see vitest.config.ts); the JSX half of this screen is exercised
// live in a real browser instead, not here.

describe("Secrets screen retire-key gate", () => {
  it("names the master ring, not the sealing ring, in its rejections", () => {
    expect(checkRetireTarget("0", null).error).toContain("base secrets master key");
    expect(checkRetireTarget("x", null).error).toContain("secrets master key id");
    expect(checkRetireTarget("256", null).error).toContain("secrets master key id");
  });

  it("refuses the active master key once a rotation in this session has revealed its id", () => {
    const check = checkRetireTarget("7", 7);
    expect(check.keyId).toBeNull();
    expect(check.error).toMatch(/Rotate first/);
  });

  it("lets an id through for Fafnir to rule on while no rotation has revealed the active id", () => {
    // Fafnir publishes no listing of the master ring, so a freshly loaded screen genuinely cannot
    // know which id is active -- refusing every id there would block a legitimate retirement.
    expect(checkRetireTarget("7", null)).toEqual({ keyId: 7, error: null });
  });

  it("gates the destructive action behind the id typed back exactly", () => {
    expect(retirementConfirmed("7", 7)).toBe(true);
    expect(retirementConfirmed("", 7)).toBe(false);
    expect(retirementConfirmed("70", 7)).toBe(false);
  });
});
