import { describe, expect, it } from "vitest";
import {
  emptyStateMessage,
  FALLBACK_SEARCH,
  filterOf,
  searchSchemaWithFallback,
  targetOf,
  validCategories,
} from "./logs";
import { EMPTY_LOG_FILTER } from "@/lib/log-filter";

// Pure schema/branching logic only -- this project's vitest config is deliberately
// node-environment, store/repository-logic-only (see vitest.config.ts); the JSX-rendering half of
// this behavior (the error banner shown for an invalid search param) is verified live in a real
// browser instead, not here.
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

describe("logs route filter search params", () => {
  it("parses level and contains alongside the target", () => {
    const result = searchSchemaWithFallback.safeParse({
      kind: "node",
      nodeId: "node-1",
      level: "WARN",
      contains: "timed out",
    });
    expect(result.success).toBe(true);
    if (!result.success) return;
    expect(filterOf(result.data)).toEqual({ minLevel: "WARN", contains: "timed out" });
  });

  it("degrades an unknown level in a hand-edited URL to no constraint rather than throwing", () => {
    const result = searchSchemaWithFallback.safeParse({
      kind: "node",
      nodeId: "node-1",
      level: "SEVERE",
    });
    expect(result.success).toBe(true);
    if (!result.success) return;
    expect(filterOf(result.data)).toEqual(EMPTY_LOG_FILTER);
  });

  it("leaves the filter empty when neither parameter is present", () => {
    const result = searchSchemaWithFallback.safeParse({ kind: "controlplane" });
    expect(result.success).toBe(true);
    if (!result.success) return;
    expect(filterOf(result.data)).toEqual(EMPTY_LOG_FILTER);
  });

  it("keeps the filter parameters out of the target the repositories receive", () => {
    const result = searchSchemaWithFallback.safeParse({
      kind: "instance",
      deploymentName: "hello-deployment",
      instanceIndex: "0",
      level: "ERROR",
      contains: "boom",
    });
    expect(result.success).toBe(true);
    if (!result.success) return;
    expect(targetOf(result.data)).toEqual({
      kind: "instance",
      deploymentName: "hello-deployment",
      instanceIndex: 0,
      category: "APPLICATION",
    });
  });
});

describe("emptyStateMessage", () => {
  it("says nothing has been logged yet when no filter is active", () => {
    expect(emptyStateMessage(EMPTY_LOG_FILTER)).toBe("No log lines yet.");
  });

  it("names what was filtered on when a filter matched nothing, rather than looking broken", () => {
    expect(emptyStateMessage({ minLevel: "ERROR", contains: "boom" })).toBe(
      "No log lines matched level \u2265 ERROR, containing \u201Cboom\u201D.",
    );
  });
});
