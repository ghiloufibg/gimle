import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint, BlueprintNode } from "@/lib/blueprint";
import type { BlueprintsRepository } from "@/repositories/contracts";

const saveMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: vi.fn(),
    get: vi.fn(),
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
  it("does not clobber an edit that lands on a node while an earlier save is still in flight", async () => {
    const node = workloadNode("n1");
    useBlueprintStore.setState({ blueprint: blueprint({ nodes: [node] }), dirty: true });

    const first = deferred<void>();
    const second = deferred<void>();
    saveMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);

    // Save #1 snapshots the blueprint before either field edit below has happened.
    const saveCall1 = useBlueprintStore.getState().save();

    // Two rapid field edits on the freshly-added node, exactly as two quick keystrokes in the
    // Inspector would produce -- each is its own updateNode call.
    useBlueprintStore.getState().updateNode("n1", { name: "renamed" } as never);
    useBlueprintStore.getState().updateNode("n1", {
      module: { name: "com.example.module", version: "1.0.0" },
    } as never);

    // Save #2 snapshots the blueprint with both edits already applied.
    const saveCall2 = useBlueprintStore.getState().save();

    // Save #2's network call resolves first -- entirely plausible for two overlapping requests.
    second.resolve();
    await saveCall2;
    // Save #1's call, kicked off earlier against a stale snapshot, resolves after.
    first.resolve();
    await saveCall1;

    const after = useBlueprintStore.getState().blueprint!.nodes[0].data as {
      name: string;
      module: { name: string };
    };
    expect(after.name).toBe("renamed");
    expect(after.module.name).toBe("com.example.module");
  });

  it("marks the blueprint saved only when nothing has changed since that save's own snapshot", async () => {
    useBlueprintStore.setState({ blueprint: blueprint(), dirty: true });
    const pending = deferred<void>();
    saveMock.mockReturnValueOnce(pending.promise);

    const saveCall = useBlueprintStore.getState().save();
    useBlueprintStore.getState().patchBlueprint({ name: "edited-during-save" });

    pending.resolve();
    await saveCall;

    // The edit that landed after this save's own snapshot was taken is still unsaved.
    expect(useBlueprintStore.getState().dirty).toBe(true);
    expect(useBlueprintStore.getState().blueprint?.name).toBe("edited-during-save");
  });
});
