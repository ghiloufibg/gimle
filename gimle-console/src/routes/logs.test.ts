import { describe, expect, it } from "vitest";
import { FALLBACK_SEARCH, searchSchemaWithFallback, validCategories } from "./logs";

// F-03/F-05a regressions. Pure schema/branching logic only -- this project's vitest config is
// deliberately node-environment, store/repository-logic-only (see vitest.config.ts); the JSX
// rendering half of these fixes (F-05b's error banner) is verified live per the QA fix plan's
// verification section, not here.
describe("logs route search schema", () => {
  it("falls back to the control plane target instead of throwing when no search params are given", () => {
    const result = searchSchemaWithFallback.safeParse({});
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data).toEqual(FALLBACK_SEARCH);
    }
  });

  it("still parses a well-formed instance target normally", () => {
    const result = searchSchemaWithFallback.safeParse({
      kind: "instance",
      deploymentName: "hello-deployment",
      instanceIndex: "0",
    });
    expect(result.success).toBe(true);
    if (result.success && result.data.kind === "instance") {
      expect(result.data.deploymentName).toBe("hello-deployment");
      expect(result.data.instanceIndex).toBe(0);
      expect(result.data.category).toBe("APPLICATION");
    }
  });
});

describe("validCategories", () => {
  it("restricts controlplane targets to PLATFORM only, matching what the API actually accepts", () => {
    expect(validCategories("controlplane")).toEqual(["PLATFORM"]);
  });

  it("still offers both categories for node targets", () => {
    expect(validCategories("node")).toEqual(["PLATFORM", "SYSTEM"]);
  });

  it("still offers both categories for instance targets", () => {
    expect(validCategories("instance")).toEqual(["APPLICATION", "PLATFORM"]);
  });
});
