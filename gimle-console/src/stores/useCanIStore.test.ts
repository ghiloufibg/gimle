import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  canIRepo: { check: vi.fn() },
}));

import { canIRepo } from "@/repositories";
import { useAuthStore } from "./useAuthStore";
import { useCanIStore } from "./useCanIStore";

describe("useCanIStore", () => {
  beforeEach(() => {
    useCanIStore.setState({ results: {} });
    useAuthStore.setState({ principal: null });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("starts with no cached answer, which callers treat as unknown/disabled", () => {
    expect(useCanIStore.getState().results["TENANT:DELETE:acme"]).toBeUndefined();
  });

  it("caches the server's answer once resolved", async () => {
    vi.mocked(canIRepo.check).mockResolvedValueOnce(true);

    useCanIStore.getState().check("TENANT", "DELETE", "acme");
    await vi.waitFor(() => {
      expect(useCanIStore.getState().results["TENANT:DELETE:acme"]).toBe(true);
    });
  });

  it("asks at most once for the same question across repeated calls", async () => {
    vi.mocked(canIRepo.check).mockResolvedValue(true);

    useCanIStore.getState().check("TENANT", "WRITE", "acme");
    useCanIStore.getState().check("TENANT", "WRITE", "acme");
    await vi.waitFor(() => {
      expect(useCanIStore.getState().results["TENANT:WRITE:acme"]).toBe(true);
    });

    expect(canIRepo.check).toHaveBeenCalledTimes(1);
  });

  it("distinguishes an unscoped question from the same one scoped to a tenant", async () => {
    vi.mocked(canIRepo.check).mockResolvedValueOnce(false).mockResolvedValueOnce(true);

    useCanIStore.getState().check("DEPLOYMENT", "WRITE");
    useCanIStore.getState().check("DEPLOYMENT", "WRITE", "acme");
    await vi.waitFor(() => {
      expect(useCanIStore.getState().results["DEPLOYMENT:WRITE:"]).toBe(false);
      expect(useCanIStore.getState().results["DEPLOYMENT:WRITE:acme"]).toBe(true);
    });
  });

  it("leaves the question unresolved (not cached false) when the server can't be reached", async () => {
    vi.mocked(canIRepo.check).mockRejectedValueOnce(new Error("unreachable"));

    useCanIStore.getState().check("TENANT", "DELETE", "acme");
    await vi.waitFor(() => {
      expect(canIRepo.check).toHaveBeenCalledTimes(1);
    });

    expect(useCanIStore.getState().results["TENANT:DELETE:acme"]).toBeUndefined();
  });

  it("clears every cached answer when the signed-in principal changes", async () => {
    vi.mocked(canIRepo.check).mockResolvedValueOnce(true);
    useCanIStore.getState().check("TENANT", "DELETE", "acme");
    await vi.waitFor(() => {
      expect(useCanIStore.getState().results["TENANT:DELETE:acme"]).toBe(true);
    });

    useAuthStore.setState({ principal: { username: "another-op", groups: [] } });

    expect(useCanIStore.getState().results).toEqual({});
  });
});
