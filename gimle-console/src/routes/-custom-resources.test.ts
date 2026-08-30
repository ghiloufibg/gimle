import { describe, expect, it } from "vitest";
import { resolvePath, toYaml } from "./custom-resources";
import type { CustomResourceItem } from "@/types";

// Pure path-resolution/rendering logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the JSX half of this screen is exercised live in a
// real browser instead, not here.

const resource: CustomResourceItem = {
  kind: "custom.Greeting",
  name: "hello-world",
  tenantId: "team-a",
  generation: 2,
  spec: { message: "hello", repeat: 3, nested: { deep: true }, list: [1, 2] },
  status: { timesSaid: 3, observedGeneration: 2 },
};

describe("resolvePath", () => {
  it("walks a dotted path into spec and status alike", () => {
    expect(resolvePath(resource, "spec.message")).toBe("hello");
    expect(resolvePath(resource, "status.timesSaid")).toBe(3);
    expect(resolvePath(resource, "spec.nested.deep")).toBe(true);
  });

  it("answers null for a missing segment rather than throwing", () => {
    expect(resolvePath(resource, "spec.noSuchField")).toBeNull();
    expect(resolvePath(resource, "status.a.b.c")).toBeNull();
  });

  it("answers null when a segment lands on a non-object rather than descending into it", () => {
    expect(resolvePath(resource, "spec.message.length")).toBeNull();
    expect(resolvePath(resource, "spec.list.0")).toBeNull();
  });

  it("resolves against the whole resource, so top-level fields work too", () => {
    expect(resolvePath(resource, "generation")).toBe(2);
    expect(resolvePath(resource, "name")).toBe("hello-world");
  });

  it("answers null against a null status", () => {
    expect(resolvePath({ ...resource, status: null }, "status.timesSaid")).toBeNull();
  });
});

describe("toYaml", () => {
  it("renders a flat object one key per line", () => {
    expect(toYaml({ message: "hello", repeat: 3, on: true })).toBe(
      'message: "hello"\nrepeat: 3\non: true',
    );
  });

  it("indents nested objects under their key", () => {
    expect(toYaml({ outer: { inner: 1 } })).toBe("outer:\n  inner: 1");
  });

  it("renders scalar lists as dash items", () => {
    expect(toYaml({ items: [1, "two"] })).toBe('items:\n  - 1\n  - "two"');
  });

  it("renders empty containers explicitly rather than as blank lines", () => {
    expect(toYaml({})).toBe("{}");
    expect(toYaml({ empty: [] })).toBe("empty:\n  []");
  });
});
