import { describe, expect, it } from "vitest";

import { createBlueprint } from "./blueprint";

describe("createBlueprint's default runtime.dataRoot", () => {
  it("scopes the default data root to this blueprint's own id", () => {
    const bp = createBlueprint("test");

    expect(bp.runtime.dataRoot).toBe(`~/.gimle/data/${bp.id}`);
  });

  it("gives two different blueprints distinct default data roots", () => {
    const a = createBlueprint("a");
    const b = createBlueprint("b");

    expect(a.runtime.dataRoot).not.toBe(b.runtime.dataRoot);
  });

  it("scopes the empty-starter blueprint's default data root the same way", () => {
    const bp = createBlueprint("test", { empty: true });

    expect(bp.runtime.dataRoot).toBe(`~/.gimle/data/${bp.id}`);
  });

  it("gives two empty-starter blueprints distinct default data roots too", () => {
    const a = createBlueprint("a", { empty: true });
    const b = createBlueprint("b", { empty: true });

    expect(a.runtime.dataRoot).not.toBe(b.runtime.dataRoot);
  });
});
