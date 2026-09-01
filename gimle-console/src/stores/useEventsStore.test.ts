import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  eventsRepo: { fetchForInstance: vi.fn() },
}));

import { eventsRepo } from "@/repositories";
import { instanceKey, useEventsStore } from "./useEventsStore";
import type { InstanceEvent } from "@/types";

const OLDER: InstanceEvent = {
  id: "evt-1",
  deploymentName: "greeter-consumer",
  instanceIndex: 0,
  kind: "STARTING",
  message: "starting instance",
  occurredAtEpochMilli: 1_000,
};
const NEWER: InstanceEvent = {
  id: "evt-2",
  deploymentName: "greeter-consumer",
  instanceIndex: 0,
  kind: "TRANSITION_FAILED",
  message: "could not resolve module artifact",
  causeSummary: "GimleResolutionException: no artifact for greeter-consumer@1.0.0",
  occurredAtEpochMilli: 2_000,
};

describe("useEventsStore", () => {
  beforeEach(() => {
    useEventsStore.getState().reset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("load surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(eventsRepo.fetchForInstance).mockRejectedValueOnce(
      new Error("control plane unreachable"),
    );

    await useEventsStore.getState().load("greeter-consumer", 0);

    const state = useEventsStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
    expect(state.loaded).toBe(false);
    expect(state.items).toEqual([]);
  });

  it("a successful load sorts the timeline newest-first regardless of response order", async () => {
    vi.mocked(eventsRepo.fetchForInstance).mockResolvedValueOnce([OLDER, NEWER]);

    await useEventsStore.getState().load("greeter-consumer", 0);

    const state = useEventsStore.getState();
    expect(state.items.map((e) => e.id)).toEqual(["evt-2", "evt-1"]);
    expect(state.loaded).toBe(true);
    expect(state.error).toBeNull();
  });

  it("passes the instance's own tenant through to the repository", async () => {
    vi.mocked(eventsRepo.fetchForInstance).mockResolvedValueOnce([]);

    await useEventsStore.getState().load("greeter-provider", 2, "acme");

    expect(eventsRepo.fetchForInstance).toHaveBeenCalledWith("greeter-provider", 2, "acme");
    expect(useEventsStore.getState().key).toBe(instanceKey("greeter-provider", 2, "acme"));
  });

  it("keys the same name under different tenants distinctly", () => {
    expect(instanceKey("greeter", 0, "acme")).not.toBe(instanceKey("greeter", 0, "globex"));
    expect(instanceKey("greeter", 0)).toBe(instanceKey("greeter", 0, null));
  });

  it("discards a response that lands after the operator navigated to another instance", async () => {
    let release: (events: InstanceEvent[]) => void = () => {};
    vi.mocked(eventsRepo.fetchForInstance).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve;
      }),
    );

    const inFlight = useEventsStore.getState().load("greeter-consumer", 0);
    // Navigating away re-keys the store before the first response ever arrives.
    useEventsStore.setState({ key: instanceKey("greeter-consumer", 1), items: [], loaded: false });
    release([OLDER, NEWER]);
    await inFlight;

    expect(useEventsStore.getState().items).toEqual([]);
    expect(useEventsStore.getState().loaded).toBe(false);
  });

  it("clears a previous instance's timeline before loading the next one", async () => {
    vi.mocked(eventsRepo.fetchForInstance).mockResolvedValueOnce([NEWER]);
    await useEventsStore.getState().load("greeter-consumer", 0);
    expect(useEventsStore.getState().items).toHaveLength(1);

    vi.mocked(eventsRepo.fetchForInstance).mockResolvedValueOnce([]);
    await useEventsStore.getState().load("greeter-consumer", 1);

    expect(useEventsStore.getState().items).toEqual([]);
    expect(useEventsStore.getState().loaded).toBe(true);
  });

  it("reset clears the loaded timeline and its error", async () => {
    vi.mocked(eventsRepo.fetchForInstance).mockRejectedValueOnce(new Error("boom"));
    await useEventsStore.getState().load("greeter-consumer", 0);

    useEventsStore.getState().reset();

    expect(useEventsStore.getState()).toMatchObject({
      key: null,
      items: [],
      loading: false,
      loaded: false,
      error: null,
    });
  });
});
