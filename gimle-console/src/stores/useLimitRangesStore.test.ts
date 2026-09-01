import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  limitRangesRepo: {
    fetchAll: vi.fn(),
    fetchOne: vi.fn(),
    save: vi.fn(),
    remove: vi.fn(),
  },
}));

import { limitRangesRepo } from "@/repositories";
import { useLimitRangesStore } from "./useLimitRangesStore";

const acme = { tenantId: "acme", maxRequest: { memory: "2Gi", cpu: "2000m" } };

describe("useLimitRangesStore", () => {
  beforeEach(() => {
    useLimitRangesStore.setState({ items: [], loading: false, loaded: false, error: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("load stores the fetched ranges and marks itself loaded", async () => {
    vi.mocked(limitRangesRepo.fetchAll).mockResolvedValueOnce([acme]);

    await useLimitRangesStore.getState().load();

    const state = useLimitRangesStore.getState();
    expect(state.items).toEqual([acme]);
    expect(state.loaded).toBe(true);
    expect(state.loading).toBe(false);
  });

  it("load surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(limitRangesRepo.fetchAll).mockRejectedValueOnce(new Error("store unreachable"));

    await useLimitRangesStore.getState().load();

    const state = useLimitRangesStore.getState();
    expect(state.error).toBe("store unreachable");
    expect(state.loading).toBe(false);
    expect(state.loaded).toBe(false);
  });

  it("load is a no-op while another load is already in flight", async () => {
    useLimitRangesStore.setState({ loading: true });

    await useLimitRangesStore.getState().load();

    expect(limitRangesRepo.fetchAll).not.toHaveBeenCalled();
  });

  it("refresh re-fetches even though the store is already loaded", async () => {
    useLimitRangesStore.setState({ items: [], loaded: true });
    vi.mocked(limitRangesRepo.fetchAll).mockResolvedValueOnce([acme]);

    await useLimitRangesStore.getState().refresh();

    expect(useLimitRangesStore.getState().items).toEqual([acme]);
  });

  it("save re-reads the list so the row reflects what the server actually stored", async () => {
    vi.mocked(limitRangesRepo.save).mockResolvedValueOnce(undefined);
    vi.mocked(limitRangesRepo.fetchAll).mockResolvedValueOnce([acme]);

    await useLimitRangesStore.getState().save(acme);

    expect(limitRangesRepo.save).toHaveBeenCalledWith(acme);
    expect(useLimitRangesStore.getState().items).toEqual([acme]);
  });

  it("save surfaces a rejection as store.error and rethrows for the caller's toast", async () => {
    vi.mocked(limitRangesRepo.save).mockRejectedValueOnce(
      new Error("min request memory exceeds max limit memory"),
    );

    await expect(useLimitRangesStore.getState().save(acme)).rejects.toThrow(
      "min request memory exceeds max limit memory",
    );
    const state = useLimitRangesStore.getState();
    expect(state.error).toBe("min request memory exceeds max limit memory");
    expect(state.loading).toBe(false);
  });

  it("remove drops just that tenant's range from the list", async () => {
    useLimitRangesStore.setState({ items: [acme, { tenantId: "beta" }] });
    vi.mocked(limitRangesRepo.remove).mockResolvedValueOnce(undefined);

    await useLimitRangesStore.getState().remove("acme");

    expect(useLimitRangesStore.getState().items).toEqual([{ tenantId: "beta" }]);
  });

  it("remove surfaces a rejection as store.error and leaves the list untouched", async () => {
    useLimitRangesStore.setState({ items: [acme] });
    vi.mocked(limitRangesRepo.remove).mockRejectedValueOnce(new Error("forbidden"));

    await expect(useLimitRangesStore.getState().remove("acme")).rejects.toThrow("forbidden");
    const state = useLimitRangesStore.getState();
    expect(state.error).toBe("forbidden");
    expect(state.items).toEqual([acme]);
  });

  it("fetchOne rethrows rather than raising a screen-wide error banner", async () => {
    vi.mocked(limitRangesRepo.fetchOne).mockRejectedValueOnce(new Error("no such limit range"));

    await expect(useLimitRangesStore.getState().fetchOne("gone")).rejects.toThrow(
      "no such limit range",
    );
    expect(useLimitRangesStore.getState().error).toBeNull();
  });
});
