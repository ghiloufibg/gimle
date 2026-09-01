import { describe, expect, it } from "vitest";
import { hasUnsavedInput } from "./form-state";

const EMPTY = { tenantId: "", deploymentNames: "", port: "" };

describe("hasUnsavedInput", () => {
  it("is false for an untouched form, so a screen keeps auto-refreshing", () => {
    expect(hasUnsavedInput(null, "", EMPTY)).toBe(false);
  });

  it("is true while an existing row is being edited", () => {
    expect(hasUnsavedInput("checkout", "checkout", EMPTY)).toBe(true);
  });

  it("is true as soon as a name has been typed", () => {
    expect(hasUnsavedInput(null, "checkout", EMPTY)).toBe(true);
  });

  it("is true when any other field has been filled in", () => {
    expect(hasUnsavedInput(null, "", { ...EMPTY, port: "8080" })).toBe(true);
  });

  it("treats whitespace-only input as untouched", () => {
    expect(hasUnsavedInput(null, "   ", { ...EMPTY, tenantId: " " })).toBe(false);
  });
});
