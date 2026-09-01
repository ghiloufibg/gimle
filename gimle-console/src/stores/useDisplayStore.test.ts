import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useDisplayStore } from "./useDisplayStore";

const KEY = "gimle.display";

function stubLocalStorage(seed: Record<string, string> = {}) {
  const data = new Map(Object.entries(seed));
  const localStorage = {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  };
  vi.stubGlobal("window", { localStorage });
  return data;
}

beforeEach(() => {
  useDisplayStore.setState({
    mode: "hud",
    density: "compact",
    autoRefresh: true,
    initialized: false,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useDisplayStore auto-refresh preference", () => {
  it("is on for an operator who has never touched it", () => {
    stubLocalStorage();

    useDisplayStore.getState().init();

    expect(useDisplayStore.getState().autoRefresh).toBe(true);
  });

  it("stays on across a reload for a stored preference that predates the setting", () => {
    stubLocalStorage({ [KEY]: JSON.stringify({ mode: "signal", density: "roomy" }) });

    useDisplayStore.getState().init();

    const state = useDisplayStore.getState();
    expect(state.mode).toBe("signal");
    expect(state.density).toBe("roomy");
    expect(state.autoRefresh).toBe(true);
  });

  it("honours a stored off preference", () => {
    stubLocalStorage({ [KEY]: JSON.stringify({ autoRefresh: false }) });

    useDisplayStore.getState().init();

    expect(useDisplayStore.getState().autoRefresh).toBe(false);
  });

  it("falls back to the defaults on a malformed stored preference", () => {
    stubLocalStorage({ [KEY]: "{not json" });

    useDisplayStore.getState().init();

    const state = useDisplayStore.getState();
    expect(state.autoRefresh).toBe(true);
    expect(state.mode).toBe("hud");
  });

  it("persists the switch alongside the other display preferences, not instead of them", () => {
    const stored = stubLocalStorage();
    useDisplayStore.getState().init();

    useDisplayStore.getState().setMode("signal");
    useDisplayStore.getState().setAutoRefresh(false);
    useDisplayStore.getState().setDensity("roomy");

    expect(JSON.parse(stored.get(KEY) as string)).toEqual({
      mode: "signal",
      density: "roomy",
      autoRefresh: false,
    });
    expect(useDisplayStore.getState().autoRefresh).toBe(false);
  });
});
