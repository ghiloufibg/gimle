import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint } from "@/lib/blueprint";
import type { BlueprintsRepository } from "@/repositories/contracts";

const listMock = vi.fn();
const getMock = vi.fn();
const createMock = vi.fn();
const deleteMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: (...args: unknown[]) => listMock(...args),
    get: (...args: unknown[]) => getMock(...args),
    create: (...args: unknown[]) => createMock(...args),
    save: vi.fn(),
    delete: (...args: unknown[]) => deleteMock(...args),
  } satisfies BlueprintsRepository,
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useBlueprintsListStore } = await import("./useBlueprintsListStore");

const minimalBlueprint: Blueprint = {
  id: "bp-source",
  name: "source",
  version: "1.0.0",
  transport: "plaintext",
  runtime: { dataRoot: "~/.gimle/data" },
  nodes: [],
  edges: [],
  updatedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  listMock.mockReset();
  getMock.mockReset();
  createMock.mockReset();
  deleteMock.mockReset();
  listMock.mockResolvedValue([]);
  createMock.mockImplementation((bp: Blueprint) =>
    Promise.resolve({ id: bp.id, name: bp.name, version: bp.version, updatedAt: bp.updatedAt }),
  );
  useBlueprintsListStore.setState({
    blueprints: [],
    details: {},
    loading: false,
    error: null,
    errorTitle: null,
  });
});

describe("useBlueprintsListStore.refresh", () => {
  it("populates blueprints and clears loading on success", async () => {
    listMock.mockResolvedValue([
      { id: "b1", name: "one", version: "1.0.0", updatedAt: "2026-01-01T00:00:00Z" },
    ]);

    await useBlueprintsListStore.getState().refresh();

    const state = useBlueprintsListStore.getState();
    expect(state.blueprints).toHaveLength(1);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("surfaces a repository failure as an error message and clears loading, without throwing", async () => {
    listMock.mockRejectedValue(new Error("ivaldi unreachable"));

    await expect(useBlueprintsListStore.getState().refresh()).resolves.toBeUndefined();

    const state = useBlueprintsListStore.getState();
    expect(state.loading).toBe(false);
    expect(state.error).toBe("ivaldi unreachable");
    expect(state.blueprints).toEqual([]);
  });

  it("clears a previous error once a retry succeeds", async () => {
    listMock.mockRejectedValueOnce(new Error("boom"));
    await useBlueprintsListStore.getState().refresh();
    expect(useBlueprintsListStore.getState().error).toBe("boom");

    listMock.mockResolvedValueOnce([]);
    await useBlueprintsListStore.getState().refresh();
    expect(useBlueprintsListStore.getState().error).toBeNull();
  });
});

describe("useBlueprintsListStore.remove", () => {
  it("clears the deleted blueprint from details on success", async () => {
    deleteMock.mockResolvedValue(undefined);
    listMock.mockResolvedValue([]);
    useBlueprintsListStore.setState({ details: { "bp-1": minimalBlueprint } });

    await useBlueprintsListStore.getState().remove("bp-1");

    expect(useBlueprintsListStore.getState().details["bp-1"]).toBeUndefined();
    expect(useBlueprintsListStore.getState().errorTitle).toBeNull();
  });

  // The backend refuses (409) a delete while a run is still tracked against this blueprint --
  // discovered live: the route's own client-side pre-check only catches a run *this* browser
  // session started, so a run tracked by another tab reached here as an unhandled rejection with
  // nothing shown to the operator at all, the blueprint silently staying undeletable.
  it("surfaces a refused delete with its own title, naming what the backend said, rather than throwing unhandled", async () => {
    deleteMock.mockRejectedValue(
      new Error("blueprint bp-1 has 1 run(s) this process is tracking (run-1) -- stop them first"),
    );
    useBlueprintsListStore.setState({ details: { "bp-1": minimalBlueprint } });

    await expect(useBlueprintsListStore.getState().remove("bp-1")).resolves.toBeUndefined();

    expect(useBlueprintsListStore.getState().errorTitle).toBe("Couldn't delete blueprint");
    expect(useBlueprintsListStore.getState().error).toContain("run-1");
    // The blueprint was never actually removed -- still readable, exactly as the refusal says.
    expect(useBlueprintsListStore.getState().details["bp-1"]).toBe(minimalBlueprint);
  });
});

// create() now refuses (409) a request naming an id already on disk instead of silently minting
// a different one -- so a caller reusing the source's own id, as duplicate/importBlueprint did
// before, would always collide with the very document it just read.
describe("useBlueprintsListStore.duplicate", () => {
  it("mints a fresh id rather than reusing the source's own", async () => {
    getMock.mockResolvedValue(minimalBlueprint);

    await useBlueprintsListStore.getState().duplicate("bp-source");

    const sent = createMock.mock.calls[0][0] as Blueprint;
    expect(sent.id).not.toBe("bp-source");
    expect(sent.name).toBe("source-copy");
  });
});

describe("useBlueprintsListStore.importBlueprint", () => {
  it("mints a fresh id rather than the imported document's own", async () => {
    await useBlueprintsListStore.getState().importBlueprint(minimalBlueprint);

    const sent = createMock.mock.calls[0][0] as Blueprint;
    expect(sent.id).not.toBe("bp-source");
  });

  it("re-importing the same document twice mints two different ids, not one repeated", async () => {
    await useBlueprintsListStore.getState().importBlueprint(minimalBlueprint);
    await useBlueprintsListStore.getState().importBlueprint(minimalBlueprint);

    const first = (createMock.mock.calls[0][0] as Blueprint).id;
    const second = (createMock.mock.calls[1][0] as Blueprint).id;
    expect(first).not.toBe(second);
  });
});
