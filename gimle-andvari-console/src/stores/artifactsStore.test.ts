import { afterEach, describe, expect, it, vi } from "vitest";

import { jsonResponse, textResponse } from "@/repositories/http/testUtil";
import { useArtifactsStore } from "./artifactsStore";

// The composition root (@/repositories) always wires the Http implementation (see
// src/repositories/index.ts's own comment), so this store-level test stubs fetch directly rather
// than swapping in the Mock repository -- the same pattern gimle-fafnir-console's own
// useAuthStore.test.ts establishes.
afterEach(() => {
  vi.unstubAllGlobals();
  useArtifactsStore.setState({
    catalog: [],
    catalogLoading: false,
    error: null,
    versionsByModule: {},
    moduleLoading: false,
    moduleError: null,
    pushPending: false,
    pushError: null,
    pushConflict: false,
    deletePending: null,
  });
});

describe("useArtifactsStore", () => {
  it("loadCatalog populates the catalog from the real HTTP repository", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => jsonResponse(["com.example.greeter-provider"])),
    );

    await useArtifactsStore.getState().loadCatalog();

    expect(useArtifactsStore.getState().catalog).toEqual(["com.example.greeter-provider"]);
    expect(useArtifactsStore.getState().error).toBeNull();
  });

  it("a 409 push conflict surfaces the immutable-version explanation, not a generic error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        textResponse("artifact com.example.app:1.0.0 already exists with sha256 abc123", 409),
      ),
    );

    const file = new File(["x"], "a.jar");
    const result = await useArtifactsStore.getState().push("com.example.app", "1.0.0", file);

    expect(result).toBeNull();
    expect(useArtifactsStore.getState().pushConflict).toBe(true);
    expect(useArtifactsStore.getState().pushError).toContain("immutable once pushed");
  });

  it("loadModule surfaces a failed fetch into moduleError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => textResponse("unknown module does.not.exist", 404)),
    );

    await useArtifactsStore.getState().loadModule("does.not.exist");

    expect(useArtifactsStore.getState().moduleError).toContain("unknown module");
  });
});
