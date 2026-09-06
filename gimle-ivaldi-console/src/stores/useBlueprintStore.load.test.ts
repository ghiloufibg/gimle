import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint } from "@/lib/blueprint";
import type { BlueprintsRepository } from "@/repositories/contracts";

const getMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: vi.fn(),
    get: (...args: unknown[]) => getMock(...args),
    create: vi.fn(),
    save: vi.fn(),
    delete: vi.fn(),
  } satisfies BlueprintsRepository,
  hilmirValidator: { mode: "http", baseUrl: null, validate: vi.fn() },
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useBlueprintStore } = await import("./useBlueprintStore");

beforeEach(() => {
  getMock.mockReset();
  useBlueprintStore.setState({
    blueprint: null,
    selectedId: null,
    selectedIds: [],
    selectedEdgeIds: [],
    dirty: false,
    past: [],
    future: [],
    recoverableDraft: null,
  });
});

describe("useBlueprintStore.load", () => {
  it("opens a blueprint the API's own store contract allows through with no runtime/version/transport", async () => {
    // BlueprintStore documents its own body as opaque JSON it never validates -- a bare
    // POST /api/blueprints body from outside the console (or from before those fields existed)
    // is exactly what it returns back verbatim on GET.
    getMock.mockResolvedValue({
      id: "bp-bare",
      name: "Bare Blueprint",
      nodes: [],
      edges: [],
    } as unknown as Blueprint);

    await useBlueprintStore.getState().load("bp-bare");

    const bp = useBlueprintStore.getState().blueprint;
    expect(bp).not.toBeNull();
    expect(bp?.runtime.dataRoot).toBeTruthy();
    expect(bp?.version).toBeTruthy();
    expect(bp?.transport).toBe("plaintext");
  });

  it("leaves an already-complete blueprint's own fields untouched", async () => {
    const full: Blueprint = {
      id: "bp-full",
      name: "Full",
      version: "2.0.0",
      transport: "mtls",
      runtime: { dataRoot: "/custom/root", classpath: "/opt/jars" },
      nodes: [],
      edges: [],
      updatedAt: "2026-01-01T00:00:00Z",
    };
    getMock.mockResolvedValue(full);

    await useBlueprintStore.getState().load("bp-full");

    expect(useBlueprintStore.getState().blueprint).toEqual(full);
  });
});
