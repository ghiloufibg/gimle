import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint, BlueprintNode } from "@/lib/blueprint";
import type { BlueprintsRepository } from "@/repositories/contracts";

const saveMock = vi.fn();
const getMock = vi.fn();

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
const { ApiError } = await import("@/repositories/apiClient");

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

function workloadNode(id: string): BlueprintNode {
  return {
    id,
    kind: "deployment",
    position: { x: 0, y: 0 },
    data: {
      name: "deployment-1",
      tenantId: "",
      module: { name: "your.module", version: "1.0.0" },
      artifact: { source: "registry" },
      replicas: 1,
      resources: {
        request: { memory: "128Mi", cpu: "100m" },
        limit: { memory: "256Mi", cpu: "500m" },
      },
    } as never,
  };
}

function blueprint(overrides: Partial<Blueprint> = {}): Blueprint {
  return {
    id: "bp-save",
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

/** Resolves once someone calls its own `resolve`, letting a test control completion order. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

beforeEach(() => {
  saveMock.mockReset();
  getMock.mockReset();
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

describe("useBlueprintStore.save", () => {
  it("sends the previously-known updatedAt as the save's own optimistic-concurrency precondition", async () => {
    useBlueprintStore.setState({
      blueprint: blueprint({ updatedAt: "2026-01-01T00:00:00Z" }),
      dirty: true,
    });
    saveMock.mockResolvedValue(undefined);

    await useBlueprintStore.getState().save();

    expect(saveMock).toHaveBeenCalledTimes(1);
    expect(saveMock.mock.calls[0][1]).toBe("2026-01-01T00:00:00Z");
  });

  it("coalesces a save() called while one is already in flight into a single network write", async () => {
    useBlueprintStore.setState({ blueprint: blueprint(), dirty: true });
    const pending = deferred<void>();
    saveMock.mockReturnValueOnce(pending.promise);

    const first = useBlueprintStore.getState().save();
    const second = useBlueprintStore.getState().save();

    pending.resolve();
    await Promise.all([first, second]);

    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it("does not clobber an edit that lands on a node while a save is still in flight", async () => {
    const node = workloadNode("n1");
    useBlueprintStore.setState({ blueprint: blueprint({ nodes: [node] }), dirty: true });
    const pending = deferred<void>();
    saveMock.mockReturnValueOnce(pending.promise);

    const saveCall = useBlueprintStore.getState().save();
    // A field edit landing mid-flight -- the exact "two rapid Inspector edits on a freshly-added
    // node" shape, except here the second one arrives while the first save's network round trip
    // is still outstanding.
    useBlueprintStore.getState().updateNode("n1", { name: "renamed" } as never);

    pending.resolve();
    await saveCall;

    expect((useBlueprintStore.getState().blueprint!.nodes[0].data as { name: string }).name).toBe(
      "renamed",
    );
  });

  it("automatically re-saves once a save in flight completes if an edit made it dirty again", async () => {
    useBlueprintStore.setState({ blueprint: blueprint(), dirty: true });
    const first = deferred<void>();
    saveMock.mockReturnValueOnce(first.promise).mockResolvedValueOnce(undefined);

    const saveCall = useBlueprintStore.getState().save();
    useBlueprintStore.getState().patchBlueprint({ name: "edited-during-first-save" });

    first.resolve();
    await saveCall;
    // The follow-up save the completion triggers is fired-and-forgotten -- wait for it directly.
    await vi.waitFor(() => expect(saveMock).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(useBlueprintStore.getState().dirty).toBe(false));

    expect(saveMock.mock.calls[1][0]).toMatchObject({ name: "edited-during-first-save" });
  });

  it("recovers a 409 conflict as the same draft-restore dialog, without overwriting either copy", async () => {
    const mine = blueprint({ name: "my-tab-edit", updatedAt: "2026-01-01T00:00:00Z" });
    useBlueprintStore.setState({ blueprint: mine, dirty: true });
    saveMock.mockRejectedValueOnce(new ApiError(409, "stale write"));
    const serverCopy = blueprint({ name: "someone-elses-save", updatedAt: "2026-01-01T00:00:05Z" });
    getMock.mockResolvedValue(serverCopy);

    await useBlueprintStore.getState().save();

    // The server's current copy is what's shown -- not silently overwritten by this tab's edit.
    expect(useBlueprintStore.getState().blueprint?.name).toBe("someone-elses-save");
    expect(useBlueprintStore.getState().dirty).toBe(false);
    // This tab's own edit survives as a recoverable draft rather than being silently discarded.
    expect(useBlueprintStore.getState().recoverableDraft?.name).toBe("my-tab-edit");
  });

  it("keeps this tab's own edit shown, still dirty, if the server copy can't even be fetched after a 409", async () => {
    const mine = blueprint({ name: "my-tab-edit", updatedAt: "2026-01-01T00:00:00Z" });
    useBlueprintStore.setState({ blueprint: mine, dirty: true });
    saveMock.mockRejectedValueOnce(new ApiError(409, "stale write"));
    getMock.mockRejectedValue(new Error("network down"));

    await useBlueprintStore.getState().save();

    expect(useBlueprintStore.getState().blueprint?.name).toBe("my-tab-edit");
    expect(useBlueprintStore.getState().dirty).toBe(true);
    expect(useBlueprintStore.getState().recoverableDraft?.name).toBe("my-tab-edit");
  });

  it("restoreDraft after a 409 saves cleanly instead of looping on the draft's own stale updatedAt", async () => {
    const mine = blueprint({ name: "my-tab-edit", updatedAt: "2026-01-01T00:00:00Z" });
    useBlueprintStore.setState({ blueprint: mine, dirty: true });
    saveMock.mockRejectedValueOnce(new ApiError(409, "stale write"));
    const serverCopy = blueprint({ name: "someone-elses-save", updatedAt: "2026-01-01T00:00:05Z" });
    getMock.mockResolvedValue(serverCopy);

    await useBlueprintStore.getState().save();
    useBlueprintStore.getState().restoreDraft();

    saveMock.mockResolvedValueOnce(undefined);
    await useBlueprintStore.getState().save();

    expect(saveMock).toHaveBeenCalledTimes(2);
    // The precondition sent on the retried save is the fresh server updatedAt fetched during the
    // conflict, not the draft's own stale one -- otherwise this would 409 again forever.
    expect(saveMock.mock.calls[1][1]).toBe("2026-01-01T00:00:05Z");
    expect(saveMock.mock.calls[1][0]).toMatchObject({ name: "my-tab-edit" });
  });

  it("lets a genuine failure (not a conflict) propagate rather than swallowing it", async () => {
    useBlueprintStore.setState({ blueprint: blueprint(), dirty: true });
    saveMock.mockRejectedValueOnce(new Error("network down"));

    await expect(useBlueprintStore.getState().save()).rejects.toThrow("network down");
  });
});
