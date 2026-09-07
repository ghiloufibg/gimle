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
  it("persistDraftNow writes the current blueprint to localStorage with a fresh local timestamp", () => {
    const bp = blueprint();
    useBlueprintStore.setState({ blueprint: bp });

    const before = Date.now();
    useBlueprintStore.getState().persistDraftNow();
    const after = Date.now();

    const envelope = JSON.parse(localStorage.getItem("ivaldi:draft:bp-draft")!) as {
      blueprint: unknown;
      savedAt: number;
    };
    expect(envelope.blueprint).toEqual(bp);
    expect(envelope.savedAt).toBeGreaterThanOrEqual(before);
    expect(envelope.savedAt).toBeLessThanOrEqual(after);
  });

  it("load surfaces a locally-persisted draft touched after the server's own copy was last saved", async () => {
    // The draft carries the *same* updatedAt as the server copy -- exactly what a draft written
    // just before a crash looks like, since only a completed save ever advances updatedAt. Only
    // the draft's own local savedAt, set later than the mocked server fetch below, makes it
    // recoverable.
    const draft = blueprint({ updatedAt: "2026-01-01T00:00:00Z", name: "edited-locally" });
    localStorage.setItem(
      "ivaldi:draft:bp-draft",
      JSON.stringify({ blueprint: draft, savedAt: Date.now() }),
    );
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-01T00:00:00Z" }));

    await useBlueprintStore.getState().load("bp-draft");

    expect(useBlueprintStore.getState().recoverableDraft).toEqual(draft);
    // The server's own copy is what's shown until the user chooses to restore.
    expect(useBlueprintStore.getState().blueprint?.name).toBe("test");
  });

  it("load discards a stale draft last touched no later than the server's own copy", async () => {
    const draft = blueprint({ updatedAt: "2026-01-01T00:00:00Z" });
    localStorage.setItem(
      "ivaldi:draft:bp-draft",
      JSON.stringify({ blueprint: draft, savedAt: new Date("2026-01-01T00:00:00Z").getTime() }),
    );
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-02T00:00:00Z" }));

    await useBlueprintStore.getState().load("bp-draft");

    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();
    expect(localStorage.getItem("ivaldi:draft:bp-draft")).toBeNull();
  });

  it("restoreDraft applies the draft as the current blueprint and marks it dirty", async () => {
    const draft = blueprint({ updatedAt: "2026-01-01T00:00:00Z", name: "edited-locally" });
    localStorage.setItem(
      "ivaldi:draft:bp-draft",
      JSON.stringify({ blueprint: draft, savedAt: Date.now() }),
    );
    getMock.mockResolvedValue(blueprint({ updatedAt: "2026-01-01T00:00:00Z" }));
    await useBlueprintStore.getState().load("bp-draft");

    useBlueprintStore.getState().restoreDraft();

    expect(useBlueprintStore.getState().blueprint?.name).toBe("edited-locally");
    expect(useBlueprintStore.getState().dirty).toBe(true);
    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();
  });

  it("discardDraft clears the stored draft without touching the loaded blueprint", async () => {
    const draft = blueprint({ updatedAt: "2026-01-01T00:00:00Z", name: "edited-locally" });
    localStorage.setItem(
      "ivaldi:draft:bp-draft",
      JSON.stringify({ blueprint: draft, savedAt: Date.now() }),
    );
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

  it("recovers an edit that never made it past a blocked save, across a reload", async () => {
    // First, an ordinary open: the server has the pristine copy.
    getMock.mockResolvedValue(blueprint());
    await useBlueprintStore.getState().load("bp-draft");
    expect(useBlueprintStore.getState().recoverableDraft).toBeNull();

    // An edit lands (this is what the designer's own debounce effect does on every dirty change:
    // persist a draft immediately, before the network save 600ms later ever gets a chance to run).
    useBlueprintStore.setState({
      blueprint: { ...useBlueprintStore.getState().blueprint!, name: "edited-before-crash" },
      dirty: true,
    });
    useBlueprintStore.getState().persistDraftNow();
    // The save never happens -- the tab is killed here, exactly as the bug describes.

    // Reload (a fresh tab reopening the same blueprint): the server still only has the pristine
    // copy, since the save was blocked.
    await useBlueprintStore.getState().load("bp-draft");

    expect(useBlueprintStore.getState().recoverableDraft?.name).toBe("edited-before-crash");

    useBlueprintStore.getState().restoreDraft();

    expect(useBlueprintStore.getState().blueprint?.name).toBe("edited-before-crash");
    expect(useBlueprintStore.getState().dirty).toBe(true);
  });
});
