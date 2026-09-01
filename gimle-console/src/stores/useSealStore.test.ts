import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  sealRepo: {
    fetchPublicKey: vi.fn(),
    rotateKey: vi.fn(),
    retireKey: vi.fn(),
  },
}));

import { sealRepo } from "@/repositories";
import { useSealStore } from "./useSealStore";
import type { SealingPublicKey } from "@/types";

function key(sealingKeyId: number, publicKey = "MIIBIjAN"): SealingPublicKey {
  return { sealingKeyId, publicKey, algorithm: "RSA-OAEP-SHA256" };
}

describe("useSealStore", () => {
  beforeEach(() => {
    useSealStore.setState({ activeKey: null, loading: false, error: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("load stores the active key and clears loading", async () => {
    vi.mocked(sealRepo.fetchPublicKey).mockResolvedValueOnce(key(3));

    await useSealStore.getState().load();

    const state = useSealStore.getState();
    expect(state.activeKey).toEqual(key(3));
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("load surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(sealRepo.fetchPublicKey).mockRejectedValueOnce(new Error("fafnir unreachable"));

    await useSealStore.getState().load();

    const state = useSealStore.getState();
    expect(state.error).toBe("fafnir unreachable");
    expect(state.loading).toBe(false);
    expect(state.activeKey).toBeNull();
  });

  it("a successful load clears any previously surfaced error", async () => {
    useSealStore.setState({ error: "stale previous error" });
    vi.mocked(sealRepo.fetchPublicKey).mockResolvedValueOnce(key(3));

    await useSealStore.getState().load();

    expect(useSealStore.getState().error).toBeNull();
  });

  it("rotate re-reads the key material rather than patching the id onto stale base64", async () => {
    useSealStore.setState({ activeKey: key(3, "OLD-KEY-MATERIAL") });
    vi.mocked(sealRepo.rotateKey).mockResolvedValueOnce({ activeSealingKeyId: 4 });
    vi.mocked(sealRepo.fetchPublicKey).mockResolvedValueOnce(key(4, "NEW-KEY-MATERIAL"));

    expect(await useSealStore.getState().rotate()).toBe(4);

    expect(sealRepo.fetchPublicKey).toHaveBeenCalledTimes(1);
    expect(useSealStore.getState().activeKey).toEqual(key(4, "NEW-KEY-MATERIAL"));
  });

  it("rotate surfaces a rejection as store.error, rethrows, and leaves the shown key alone", async () => {
    useSealStore.setState({ activeKey: key(3) });
    vi.mocked(sealRepo.rotateKey).mockRejectedValueOnce(new Error("forbidden"));

    await expect(useSealStore.getState().rotate()).rejects.toThrow("forbidden");

    expect(useSealStore.getState().error).toBe("forbidden");
    expect(useSealStore.getState().activeKey).toEqual(key(3));
    expect(sealRepo.fetchPublicKey).not.toHaveBeenCalled();
  });

  it("retire returns the id the server actually acted on", async () => {
    vi.mocked(sealRepo.retireKey).mockResolvedValueOnce({ retiredKeyId: 2 });

    expect(await useSealStore.getState().retire(2)).toBe(2);

    expect(sealRepo.retireKey).toHaveBeenCalledWith(2);
    expect(useSealStore.getState().error).toBeNull();
  });

  it("retire surfaces a rejection as store.error and rethrows so the caller can toast it", async () => {
    useSealStore.setState({ activeKey: key(3) });
    vi.mocked(sealRepo.retireKey).mockRejectedValueOnce(new Error("no sealing key with id 9"));

    await expect(useSealStore.getState().retire(9)).rejects.toThrow("no sealing key with id 9");

    // A refused retirement must leave the active key exactly as it was -- nothing was destroyed.
    expect(useSealStore.getState().error).toBe("no sealing key with id 9");
    expect(useSealStore.getState().activeKey).toEqual(key(3));
  });

  it("load is a no-op while one is already in flight", async () => {
    useSealStore.setState({ loading: true });

    await useSealStore.getState().load();

    expect(sealRepo.fetchPublicKey).not.toHaveBeenCalled();
  });
});
