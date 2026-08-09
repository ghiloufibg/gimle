import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  secretsRepo: {
    fetchPage: vi.fn(),
    fetchValue: vi.fn(),
    fetchVersions: vi.fn(),
    upsert: vi.fn(),
    remove: vi.fn(),
    rotateKey: vi.fn(),
  },
}));

import { secretsRepo } from "@/repositories";
import { useSecretsStore } from "./useSecretsStore";

describe("useSecretsStore", () => {
  beforeEach(() => {
    useSecretsStore.setState({
      tenantId: "acme",
      items: [],
      nextCursor: null,
      hasMore: true,
      loading: false,
      error: null,
      revealed: {},
      versions: {},
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadFirstPage surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(secretsRepo.fetchPage).mockRejectedValueOnce(new Error("fafnir unreachable"));

    await useSecretsStore.getState().loadFirstPage();

    const state = useSecretsStore.getState();
    expect(state.error).toBe("fafnir unreachable");
    expect(state.loading).toBe(false);
    expect(state.items).toEqual([]);
  });

  it("a successful loadFirstPage clears any previously surfaced error", async () => {
    useSecretsStore.setState({ error: "stale previous error" });
    vi.mocked(secretsRepo.fetchPage).mockResolvedValueOnce({ items: [], nextCursor: null });

    await useSecretsStore.getState().loadFirstPage();

    expect(useSecretsStore.getState().error).toBeNull();
  });

  it("reveal fetches the value on demand and caches it under the key, never eagerly", async () => {
    vi.mocked(secretsRepo.fetchValue).mockResolvedValueOnce({
      tenantId: "acme",
      key: "db.password",
      version: 1,
      value: "hunter2",
    });

    expect(useSecretsStore.getState().revealed["db.password"]).toBeUndefined();
    await useSecretsStore.getState().reveal("db.password");

    expect(useSecretsStore.getState().revealed["db.password"]?.value).toBe("hunter2");
    expect(secretsRepo.fetchValue).toHaveBeenCalledWith("acme", "db.password", undefined);
  });

  it("hide removes only the given key's cached reveal, leaving others untouched", async () => {
    useSecretsStore.setState({
      revealed: {
        "db.password": { tenantId: "acme", key: "db.password", version: 1, value: "x" },
        "api.key": { tenantId: "acme", key: "api.key", version: 1, value: "y" },
      },
    });

    useSecretsStore.getState().hide("db.password");

    const revealed = useSecretsStore.getState().revealed;
    expect(revealed["db.password"]).toBeUndefined();
    expect(revealed["api.key"]).toBeDefined();
  });

  it("upsert invalidates any cached reveal/versions for the key it just wrote", async () => {
    useSecretsStore.setState({
      revealed: {
        "db.password": { tenantId: "acme", key: "db.password", version: 1, value: "old" },
      },
      versions: { "db.password": [1] },
    });
    vi.mocked(secretsRepo.upsert).mockResolvedValueOnce({
      tenantId: "acme",
      key: "db.password",
      latestVersion: 2,
      deleted: false,
    });

    await useSecretsStore.getState().upsert("db.password", "new-value");

    const state = useSecretsStore.getState();
    expect(state.revealed["db.password"]).toBeUndefined();
    expect(state.versions["db.password"]).toBeUndefined();
    expect(state.items.find((i) => i.key === "db.password")?.latestVersion).toBe(2);
  });

  it("remove with destroy=false marks the item deleted instead of removing it from items", async () => {
    useSecretsStore.setState({
      items: [{ tenantId: "acme", key: "db.password", latestVersion: 1, deleted: false }],
    });
    vi.mocked(secretsRepo.remove).mockResolvedValueOnce(undefined);

    await useSecretsStore.getState().remove("db.password", false);

    const state = useSecretsStore.getState();
    expect(state.items).toHaveLength(1);
    expect(state.items[0].deleted).toBe(true);
  });

  it("remove with destroy=true removes the item from items entirely", async () => {
    useSecretsStore.setState({
      items: [{ tenantId: "acme", key: "db.password", latestVersion: 1, deleted: false }],
    });
    vi.mocked(secretsRepo.remove).mockResolvedValueOnce(undefined);

    await useSecretsStore.getState().remove("db.password", true);

    expect(useSecretsStore.getState().items).toHaveLength(0);
  });
});
