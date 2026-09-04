import { beforeEach, describe, expect, it, vi } from "vitest";

import type { BlueprintsRepository } from "@/repositories/contracts";

const listMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: (...args: unknown[]) => listMock(...args),
    get: vi.fn(),
    create: vi.fn(),
    save: vi.fn(),
    delete: vi.fn(),
  } satisfies BlueprintsRepository,
}));

// Imported after the mock so the store picks up the mocked repository module.
const { useBlueprintsListStore } = await import("./useBlueprintsListStore");

beforeEach(() => {
  listMock.mockReset();
  useBlueprintsListStore.setState({ blueprints: [], details: {}, loading: false, error: null });
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
