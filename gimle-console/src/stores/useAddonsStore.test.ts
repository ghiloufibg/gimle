import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import catalog from "../../public/addons.json";
import { ADDONS, addonById } from "@/addons";
import { NAV_GROUPS } from "@/lib/nav";
import { useAddonsStore } from "./useAddonsStore";

function respondWith(body: unknown, ok = true, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({ ok, status, json: async () => body }) as unknown as Response),
  );
}

describe("useAddonsStore", () => {
  beforeEach(() => {
    useAddonsStore.setState({ enabledIds: [], initialized: false });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("advertises only the ids the control plane says are enabled", async () => {
    respondWith({
      addons: [
        { id: "gateway", enabled: true },
        { id: "skald", enabled: false },
      ],
    });

    await useAddonsStore.getState().init();

    expect(useAddonsStore.getState().enabledIds).toEqual(["gateway"]);
    expect(useAddonsStore.getState().isEnabled("gateway")).toBe(true);
    // A disabled id hides its sidebar entry -- the group is built from enabledAddons() alone.
    expect(useAddonsStore.getState().isEnabled("skald")).toBe(false);
    expect(
      useAddonsStore
        .getState()
        .enabledAddons()
        .map((a) => a.id),
    ).toEqual(["gateway"]);
  });

  it("advertises nothing rather than throwing when the read fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("connection refused");
      }),
    );

    await useAddonsStore.getState().init();

    expect(useAddonsStore.getState().enabledIds).toEqual([]);
    // Still initialized, so every addon route explains itself instead of hanging on a blank screen.
    expect(useAddonsStore.getState().initialized).toBe(true);
  });

  it("treats a control plane too old to serve the route as advertising nothing", async () => {
    respondWith("not found", false, 404);

    await useAddonsStore.getState().init();

    expect(useAddonsStore.getState().enabledIds).toEqual([]);
    expect(useAddonsStore.getState().initialized).toBe(true);
  });

  it("reads once, not on every mount", async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ addons: [] }) }));
    vi.stubGlobal("fetch", fetchMock as unknown as typeof fetch);

    await useAddonsStore.getState().init();
    await useAddonsStore.getState().init();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("the addon registry", () => {
  it("agrees with addons.json on ids, so neither side can drift", () => {
    expect(ADDONS.map((a) => a.id)).toEqual(catalog.addons.map((a) => a.id));
  });

  it("carries an icon for every catalogued addon", () => {
    for (const addon of ADDONS) expect(addon.icon).toBeDefined();
  });

  it("keeps each addon's route matching its own route file's URL", () => {
    expect(addonById("gateway").route).toBe("/gateway");
    expect(addonById("skald").route).toBe("/skald");
  });

  it("places both edge addons in the group the Networking screen already renders under", () => {
    expect(addonById("gateway").group).toBe("Edge");
    expect(addonById("skald").group).toBe("Edge");
  });

  it("names a group the sidebar knows how to order, for every addon", () => {
    for (const addon of ADDONS) expect(NAV_GROUPS).toContain(addon.group);
  });

  it("refuses an id the catalog does not carry", () => {
    expect(() => addonById("yggdrasil")).toThrow(/no addon 'yggdrasil'/);
  });
});
