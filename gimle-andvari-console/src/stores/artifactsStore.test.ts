import { afterEach, describe, expect, it, vi } from "vitest";

import { jsonResponse, textResponse } from "@/repositories/http/testUtil";
import { selectRecentPushes, useArtifactsStore } from "./artifactsStore";

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

  it("download refuses to save when the fetched bytes don't match the expected sha256", async () => {
    // Deliberately never reaches the DOM-saving branch (anchor.click() etc.) -- a real mismatch
    // must throw before that point, which this also proves: the store runs under Vitest's `node`
    // environment with no `document` global, so if verification didn't short-circuit first, this
    // would fail with a ReferenceError instead of the sha256 message asserted below.
    const bytes = new TextEncoder().encode("real jar bytes");
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(bytes, { status: 200 })),
    );

    await expect(
      useArtifactsStore.getState().download("com.example.app", "1.0.0", "0".repeat(64)),
    ).rejects.toThrow(/sha256 verification/);
  });

  it("a failed catalog read leaves the overview an error to show, not an empty push list", async () => {
    // The overview's "recent pushes" panel used to render its own empty case for this, which reads
    // as "nothing has ever been pushed here" -- the one thing a failed read definitely does not
    // mean. The store has to carry the failure for the panel to be able to tell them apart.
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => textResponse("registry unavailable", 503)),
    );

    await useArtifactsStore.getState().loadAll();

    expect(useArtifactsStore.getState().error).toContain("registry unavailable");
    expect(selectRecentPushes(useArtifactsStore.getState())).toEqual([]);
  });

  it("recent pushes are the newest first across every module, bounded to the panel's own limit", async () => {
    useArtifactsStore.setState({
      versionsByModule: {
        "com.example.a": [
          {
            moduleId: "com.example.a",
            version: "1.0.0",
            sha256: "a".repeat(64),
            sizeBytes: 10,
            pushedAtEpochMilli: 1_000,
            pushedBy: "someone",
          },
        ],
        "com.example.b": [
          {
            moduleId: "com.example.b",
            version: "2.0.0",
            sha256: "b".repeat(64),
            sizeBytes: 20,
            pushedAtEpochMilli: 3_000,
            pushedBy: "someone",
          },
        ],
      },
    });

    const recent = selectRecentPushes(useArtifactsStore.getState());

    expect(recent.map((v) => v.moduleId)).toEqual(["com.example.b", "com.example.a"]);
    expect(selectRecentPushes(useArtifactsStore.getState(), 1)).toHaveLength(1);
  });
});
