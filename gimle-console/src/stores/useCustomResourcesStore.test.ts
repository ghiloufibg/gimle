import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  customResourcesRepo: {
    fetchKinds: vi.fn(),
    fetchResources: vi.fn(),
  },
}));

import { customResourcesRepo } from "@/repositories";
import { useCustomResourcesStore, resourceKey } from "./useCustomResourcesStore";
import type { CustomResourceItem, KindDefinitionSummary } from "@/types";

function definition(kindName: string): KindDefinitionSummary {
  return {
    kindName,
    scope: "Tenant",
    description: "",
    names: { shortNames: [] },
    schema: { fields: [] },
    printColumns: [],
    generation: 1,
  };
}

function resource(name: string, tenantId?: string): CustomResourceItem {
  return {
    kind: "custom.Greeting",
    name,
    tenantId,
    generation: 1,
    spec: { message: "hello" },
    status: null,
  };
}

describe("useCustomResourcesStore", () => {
  beforeEach(() => {
    useCustomResourcesStore.setState({
      kinds: [],
      catalogLoaded: false,
      selectedKindName: null,
      resources: [],
      selectedResourceKey: null,
      loading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadKinds stores the catalog and clears loading", async () => {
    vi.mocked(customResourcesRepo.fetchKinds).mockResolvedValueOnce([
      definition("custom.Greeting"),
    ]);

    await useCustomResourcesStore.getState().loadKinds();

    const state = useCustomResourcesStore.getState();
    expect(state.kinds.map((k) => k.kindName)).toEqual(["custom.Greeting"]);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("loadKinds surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(customResourcesRepo.fetchKinds).mockRejectedValueOnce(
      new Error("control plane unreachable"),
    );

    await useCustomResourcesStore.getState().loadKinds();

    const state = useCustomResourcesStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
    expect(state.kinds).toEqual([]);
  });

  it("selectKind loads that kind's instances on demand", async () => {
    vi.mocked(customResourcesRepo.fetchResources).mockResolvedValueOnce([resource("hello")]);

    await useCustomResourcesStore.getState().selectKind("custom.Greeting");

    const state = useCustomResourcesStore.getState();
    expect(customResourcesRepo.fetchResources).toHaveBeenCalledWith("custom.Greeting");
    expect(state.selectedKindName).toBe("custom.Greeting");
    expect(state.resources.map((r) => r.name)).toEqual(["hello"]);
  });

  it("selectKind drops the previous kind's rows and selection before the new ones arrive", async () => {
    useCustomResourcesStore.setState({
      selectedKindName: "custom.Other",
      resources: [resource("stale")],
      selectedResourceKey: resourceKey(resource("stale")),
    });
    let observedDuringFetch: CustomResourceItem[] = [];
    vi.mocked(customResourcesRepo.fetchResources).mockImplementationOnce(async () => {
      observedDuringFetch = useCustomResourcesStore.getState().resources;
      return [resource("hello")];
    });

    await useCustomResourcesStore.getState().selectKind("custom.Greeting");

    expect(observedDuringFetch).toEqual([]);
    expect(useCustomResourcesStore.getState().selectedResourceKey).toBeNull();
  });

  it("selectKind surfaces a rejection as store.error and clears loading", async () => {
    vi.mocked(customResourcesRepo.fetchResources).mockRejectedValueOnce(new Error("forbidden"));

    await useCustomResourcesStore.getState().selectKind("custom.Greeting");

    const state = useCustomResourcesStore.getState();
    expect(state.error).toBe("forbidden");
    expect(state.loading).toBe(false);
    expect(state.resources).toEqual([]);
  });

  it("selectKind(null) clears kind, rows and selection without hitting the repository", async () => {
    useCustomResourcesStore.setState({
      selectedKindName: "custom.Greeting",
      resources: [resource("hello")],
      selectedResourceKey: resourceKey(resource("hello")),
    });

    await useCustomResourcesStore.getState().selectKind(null);

    const state = useCustomResourcesStore.getState();
    expect(state.selectedKindName).toBeNull();
    expect(state.resources).toEqual([]);
    expect(state.selectedResourceKey).toBeNull();
    expect(customResourcesRepo.fetchResources).not.toHaveBeenCalled();
  });

  it("refreshResources keeps the detail selection when its instance survives the re-read", async () => {
    const hello = resource("hello", "team-a");
    useCustomResourcesStore.setState({
      selectedKindName: "custom.Greeting",
      resources: [hello],
      selectedResourceKey: resourceKey(hello),
    });
    vi.mocked(customResourcesRepo.fetchResources).mockResolvedValueOnce([
      resource("hello", "team-a"),
      resource("welcome", "team-a"),
    ]);

    await useCustomResourcesStore.getState().refreshResources();

    const state = useCustomResourcesStore.getState();
    expect(state.resources.map((r) => r.name)).toEqual(["hello", "welcome"]);
    expect(state.selectedResourceKey).toBe(resourceKey(hello));
  });

  it("refreshResources clears the detail selection when its instance was deleted", async () => {
    const hello = resource("hello", "team-a");
    useCustomResourcesStore.setState({
      selectedKindName: "custom.Greeting",
      resources: [hello],
      selectedResourceKey: resourceKey(hello),
    });
    vi.mocked(customResourcesRepo.fetchResources).mockResolvedValueOnce([
      resource("welcome", "team-a"),
    ]);

    await useCustomResourcesStore.getState().refreshResources();

    expect(useCustomResourcesStore.getState().selectedResourceKey).toBeNull();
  });

  it("refreshResources with no kind selected is a no-op", async () => {
    await useCustomResourcesStore.getState().refreshResources();

    expect(customResourcesRepo.fetchResources).not.toHaveBeenCalled();
  });

  it("poll re-reads the catalog, so a first read the control plane refused stops standing as the answer", async () => {
    // The empty kind list a refused catalog read leaves behind is not an answer about the cluster,
    // and nothing else on this screen would ever go back and ask again.
    vi.mocked(customResourcesRepo.fetchKinds).mockRejectedValueOnce(
      new Error("control plane responded 429: control plane at capacity"),
    );
    await useCustomResourcesStore.getState().loadKinds();
    expect(useCustomResourcesStore.getState().kinds).toEqual([]);

    vi.mocked(customResourcesRepo.fetchKinds).mockResolvedValueOnce([
      definition("custom.Greeting"),
    ]);
    await useCustomResourcesStore.getState().poll();

    const state = useCustomResourcesStore.getState();
    expect(state.kinds.map((k) => k.kindName)).toEqual(["custom.Greeting"]);
    expect(state.catalogLoaded).toBe(true);
    expect(state.error).toBeNull();
  });

  it("poll never raises the loading flag, so the table it re-reads is not blanked mid-glance", async () => {
    vi.mocked(customResourcesRepo.fetchKinds).mockImplementationOnce(async () => {
      expect(useCustomResourcesStore.getState().loading).toBe(false);
      return [definition("custom.Greeting")];
    });

    await useCustomResourcesStore.getState().poll();

    expect(useCustomResourcesStore.getState().loading).toBe(false);
  });

  it("poll re-reads the selected kind's rows too, keeping a selection that survived", async () => {
    const hello = resource("hello", "team-a");
    useCustomResourcesStore.setState({
      selectedKindName: "custom.Greeting",
      resources: [hello],
      selectedResourceKey: resourceKey(hello),
    });
    vi.mocked(customResourcesRepo.fetchKinds).mockResolvedValueOnce([
      definition("custom.Greeting"),
    ]);
    vi.mocked(customResourcesRepo.fetchResources).mockResolvedValueOnce([
      resource("hello", "team-a"),
      resource("welcome", "team-a"),
    ]);

    await useCustomResourcesStore.getState().poll();

    const state = useCustomResourcesStore.getState();
    expect(state.resources.map((r) => r.name)).toEqual(["hello", "welcome"]);
    expect(state.selectedResourceKey).toBe(resourceKey(hello));
  });

  it("two same-named resources in different tenants get distinct keys", () => {
    expect(resourceKey(resource("hello", "team-a"))).not.toBe(
      resourceKey(resource("hello", "team-b")),
    );
  });
});
