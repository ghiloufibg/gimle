import { afterEach, describe, expect, it, vi } from "vitest";

import { jsonResponse, stubFetchSequence } from "@/repositories/http/testUtil";

import { useSecretsStore } from "./useSecretsStore";

// The composition root (@/repositories) always wires the Http implementation, so this store-level
// test stubs fetch directly rather than swapping in the Mock repository -- the same boundary
// useAuthStore's own tests exercise.
afterEach(() => {
  vi.unstubAllGlobals();
  useSecretsStore.setState({
    tenantId: "",
    secrets: [],
    loading: false,
    error: null,
    revealed: {},
    writing: false,
    rotating: false,
  });
});

describe("useSecretsStore", () => {
  it("reveal stores the API's per-version objects without crashing on a bare-number assumption", async () => {
    stubFetchSequence([
      () => jsonResponse({ value: btoa("v3-final"), version: 3 }),
      () =>
        jsonResponse({
          versions: [
            { version: 1, author: "anonymous", writtenAtEpochMilli: 1 },
            { version: 2, author: "anonymous", writtenAtEpochMilli: 2 },
            { version: 3, author: "anonymous", writtenAtEpochMilli: 3 },
          ],
        }),
    ]);

    await useSecretsStore.getState().reveal("forseti-sec1");

    const revealed = useSecretsStore.getState().revealed["forseti-sec1"];
    expect(revealed?.value).toBe("v3-final");
    expect(revealed?.version).toBe(3);
    // Every entry must carry a `.version` number a <select><option value> can render -- not the
    // raw metadata object itself, which is exactly what previously blew up as React error #31.
    expect(revealed?.versions.map((v) => v.version)).toEqual([1, 2, 3]);
    revealed?.versions.forEach((v) => expect(typeof v.version).toBe("number"));
  });
});
