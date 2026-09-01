import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  secretsRepo: {
    fetchPage: vi.fn(),
    fetchValue: vi.fn(),
    fetchVersions: vi.fn(),
    upsert: vi.fn(),
    remove: vi.fn(),
    rotateKey: vi.fn(),
    retireKey: vi.fn(),
  },
}));

import { secretsRepo } from "@/repositories";
import type { SecretValue } from "@/types";
import { useSecretsStore } from "./useSecretsStore";

function revealedValue(key: string, value: string): SecretValue {
  return {
    tenantId: "acme",
    key,
    version: 1,
    value,
    type: "opaque",
    author: "alice",
    writtenAtEpochMilli: 100,
  };
}

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
      activeKeyId: null,
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
      type: "opaque",
      author: "alice",
      writtenAtEpochMilli: 100,
    });

    expect(useSecretsStore.getState().revealed["db.password"]).toBeUndefined();
    await useSecretsStore.getState().reveal("db.password");

    expect(useSecretsStore.getState().revealed["db.password"]?.value).toBe("hunter2");
    expect(secretsRepo.fetchValue).toHaveBeenCalledWith("acme", "db.password", undefined);
  });

  it("hide removes only the given key's cached reveal, leaving others untouched", async () => {
    useSecretsStore.setState({
      revealed: {
        "db.password": revealedValue("db.password", "x"),
        "api.key": revealedValue("api.key", "y"),
      },
    });

    useSecretsStore.getState().hide("db.password");

    const revealed = useSecretsStore.getState().revealed;
    expect(revealed["db.password"]).toBeUndefined();
    expect(revealed["api.key"]).toBeDefined();
  });

  it("upsert invalidates any cached reveal/versions for the key it just wrote", async () => {
    useSecretsStore.setState({
      revealed: { "db.password": revealedValue("db.password", "old") },
      versions: {
        "db.password": [{ version: 1, author: "alice", writtenAtEpochMilli: 100, type: "opaque" }],
      },
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

  it("upsert forwards a declared type through to the repository", async () => {
    vi.mocked(secretsRepo.upsert).mockResolvedValueOnce({
      tenantId: "acme",
      key: "tls.cert",
      latestVersion: 1,
      deleted: false,
    });

    await useSecretsStore.getState().upsert("tls.cert", "cert-body", "pem-certificate");

    expect(secretsRepo.upsert).toHaveBeenCalledWith(
      "acme",
      "tls.cert",
      "cert-body",
      "pem-certificate",
    );
  });

  it("loadVersions caches the full per-version record, not just the numbers", async () => {
    vi.mocked(secretsRepo.fetchVersions).mockResolvedValueOnce([
      { version: 1, author: "alice", writtenAtEpochMilli: 100, type: "opaque" },
      { version: 2, author: "bob", writtenAtEpochMilli: 200, type: "pem-certificate" },
    ]);

    await useSecretsStore.getState().loadVersions("db.password");

    expect(useSecretsStore.getState().versions["db.password"]?.[1]).toEqual({
      version: 2,
      author: "bob",
      writtenAtEpochMilli: 200,
      type: "pem-certificate",
    });
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

  it("rotateKey remembers the new active key id so the retire gate can refuse it", async () => {
    vi.mocked(secretsRepo.rotateKey).mockResolvedValueOnce(5);

    expect(await useSecretsStore.getState().rotateKey()).toBe(5);
    expect(useSecretsStore.getState().activeKeyId).toBe(5);
  });

  it("retireKey returns the id Fafnir actually retired", async () => {
    vi.mocked(secretsRepo.retireKey).mockResolvedValueOnce(3);

    expect(await useSecretsStore.getState().retireKey(3)).toBe(3);
  });

  it("retireKey propagates a rejection so the screen can report it instead of claiming success", async () => {
    vi.mocked(secretsRepo.retireKey).mockRejectedValueOnce(
      new Error("cannot retire the active secrets key 5"),
    );

    await expect(useSecretsStore.getState().retireKey(5)).rejects.toThrow(
      "cannot retire the active secrets key 5",
    );
  });
});
