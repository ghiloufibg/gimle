import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint } from "@/lib/blueprint";
import type { BlueprintsRepository } from "@/repositories/contracts";

const getMock = vi.fn();
const saveMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: vi.fn(),
    get: (...args: unknown[]) => getMock(...args),
    create: vi.fn(),
    save: (...args: unknown[]) => saveMock(...args),
    delete: vi.fn(),
  } satisfies BlueprintsRepository,
  hilmirValidator: {
    mode: "http",
    baseUrl: undefined,
    validate: vi.fn().mockResolvedValue({ problems: [] }),
  },
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useBlueprintStore } = await import("./useBlueprintStore");

class FakeLocalStorage {
  private data = new Map<string, string>();
  getItem(key: string): string | null {
    return this.data.has(key) ? this.data.get(key)! : null;
  }
  setItem(key: string, value: string): void {
    this.data.set(key, value);
  }
  removeItem(key: string): void {
    this.data.delete(key);
  }
}

function blueprint(overrides: Partial<Blueprint> = {}): Blueprint {
  return {
    id: "bp-draft",
    name: "test",
    version: "1.0.0",
    transport: "plaintext",
    runtime: { dataRoot: "~/.gimle/data" },
    nodes: [],
    edges: [],
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  getMock.mockReset();
  saveMock.mockReset();
  vi.stubGlobal("localStorage", new FakeLocalStorage());
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

describe("useBlueprintStore draft recovery", () => {
  it("persistDraftNow writes the current blueprint to localStorage", () => {
    const bp = blueprint();
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().persistDraftNow();

    expect(JSON.parse(localStorage.getItem("ivaldi:draft:bp-draft")!)).toEqual(bp);
  });

  it("load surfaces a locally-persisted draft newer than the server's own copy", async () => {
    const draft = blueprint({ updatedAt: "2026-01-02T00:00:00Z", name: "edited-locally" });
    localStorage.setItem("ivaldi:draft:bp-draft", JSON.stringify(draft));
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-01T00:00:00Z" }));

    await useBlueprintStore.getState().load("bp-draft");

    expect(useBlueprintStore.getState().recoverableDraft).toEqual(draft);
    // The server's own copy is what's shown until the user chooses to restore.
    expect(useBlueprintStore.getState().blueprint?.name).toBe("test");
  });

  it("load discards a stale draft that is no newer than the server's own copy", async () => {
    const draft = blueprint({ updatedAt: "2026-01-01T00:00:00Z" });
    localStorage.setItem("ivaldi:draft:bp-draft", JSON.stringify(draft));
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-02T00:00:00Z" }));

    await useBlueprintStore.getState().load("bp-draft");

    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();
    expect(localStorage.getItem("ivaldi:draft:bp-draft")).toBeNull();
  });

  it("restoreDraft applies the draft as the current blueprint and marks it dirty", async () => {
    const draft = blueprint({ updatedAt: "2026-01-02T00:00:00Z", name: "edited-locally" });
    localStorage.setItem("ivaldi:draft:bp-draft", JSON.stringify(draft));
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-01T00:00:00Z" }));
    await useBlueprintStore.getState().load("bp-draft");

    useBlueprintStore.getState().restoreDraft();

    expect(useBlueprintStore.getState().blueprint?.name).toBe("edited-locally");
    expect(useBlueprintStore.getState().dirty).toBe(true);
    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();
  });

  it("discardDraft clears the stored draft without touching the loaded blueprint", async () => {
    const draft = blueprint({ updatedAt: "2026-01-02T00:00:00Z", name: "edited-locally" });
    localStorage.setItem("ivaldi:draft:bp-draft", JSON.stringify(draft));
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-01T00:00:00Z" }));
    await useBlueprintStore.getState().load("bp-draft");

    useBlueprintStore.getState().discardDraft();

    expect(useBlueprintStore.getState().blueprint?.name).toBe("test");
    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();
    expect(localStorage.getItem("ivaldi:draft:bp-draft")).toBeNull();
  });

  it("save clears the locally-persisted draft once the server accepts it", async () => {
    const bp = blueprint();
    useBlueprintStore.setState({ blueprint: bp, dirty: true });
    useBlueprintStore.getState().persistDraftNow();
    saveMock.mockResolvedValue(undefined);

    await useBlueprintStore.getState().save();

    expect(localStorage.getItem("ivaldi:draft:bp-draft")).toBeNull();
  });
});
