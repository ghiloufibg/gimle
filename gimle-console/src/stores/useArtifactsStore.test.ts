import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  artifactsRepo: {
    fetchCatalog: vi.fn(),
    fetchVersions: vi.fn(),
    remove: vi.fn(),
  },
}));

import { artifactsRepo } from "@/repositories";
import { useArtifactsStore } from "./useArtifactsStore";
import type { ArtifactVersion } from "@/types";

function version(moduleId: string, v: string): ArtifactVersion {
  return {
    moduleId,
    version: v,
    sha256: "b".repeat(64),
    sizeBytes: 1024,
    pushedAtEpochMilli: 1_754_000_000_000,
    pushedBy: "ci-publisher",
  };
}

describe("useArtifactsStore", () => {
  beforeEach(() => {
    useArtifactsStore.setState({
      moduleIds: [],
      selectedModuleId: null,
      versions: [],
      loading: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("loadCatalog stores the module ids and clears loading", async () => {
    vi.mocked(artifactsRepo.fetchCatalog).mockResolvedValueOnce([
      "greeter-provider",
      "edge-router",
    ]);

    await useArtifactsStore.getState().loadCatalog();

    const state = useArtifactsStore.getState();
    expect(state.moduleIds).toEqual(["greeter-provider", "edge-router"]);
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it("loadCatalog surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(artifactsRepo.fetchCatalog).mockRejectedValueOnce(new Error("andvari unreachable"));

    await useArtifactsStore.getState().loadCatalog();

    const state = useArtifactsStore.getState();
    expect(state.error).toBe("andvari unreachable");
    expect(state.loading).toBe(false);
    expect(state.moduleIds).toEqual([]);
  });

  it("a successful loadCatalog clears any previously surfaced error", async () => {
    useArtifactsStore.setState({ error: "stale previous error" });
    vi.mocked(artifactsRepo.fetchCatalog).mockResolvedValueOnce([]);

    await useArtifactsStore.getState().loadCatalog();

    expect(useArtifactsStore.getState().error).toBeNull();
  });

  it("select loads that module's versions on demand, never eagerly with the catalog", async () => {
    vi.mocked(artifactsRepo.fetchVersions).mockResolvedValueOnce([
      version("greeter-provider", "1.0.0"),
    ]);

    await useArtifactsStore.getState().select("greeter-provider");

    const state = useArtifactsStore.getState();
    expect(artifactsRepo.fetchVersions).toHaveBeenCalledWith("greeter-provider");
    expect(state.selectedModuleId).toBe("greeter-provider");
    expect(state.versions.map((v) => v.version)).toEqual(["1.0.0"]);
  });

  it("select drops the previous module's rows before the new ones arrive", async () => {
    useArtifactsStore.setState({
      selectedModuleId: "edge-router",
      versions: [version("edge-router", "0.9.0")],
    });
    let observedDuringFetch: ArtifactVersion[] = [];
    vi.mocked(artifactsRepo.fetchVersions).mockImplementationOnce(async () => {
      observedDuringFetch = useArtifactsStore.getState().versions;
      return [version("greeter-provider", "1.0.0")];
    });

    await useArtifactsStore.getState().select("greeter-provider");

    expect(observedDuringFetch).toEqual([]);
  });

  it("select surfaces a versions rejection as store.error and clears loading", async () => {
    vi.mocked(artifactsRepo.fetchVersions).mockRejectedValueOnce(new Error("forbidden"));

    await useArtifactsStore.getState().select("greeter-provider");

    const state = useArtifactsStore.getState();
    expect(state.error).toBe("forbidden");
    expect(state.loading).toBe(false);
    expect(state.versions).toEqual([]);
  });

  it("select(null) clears the selection without hitting the repository", async () => {
    useArtifactsStore.setState({
      selectedModuleId: "edge-router",
      versions: [version("edge-router", "0.9.0")],
    });

    await useArtifactsStore.getState().select(null);

    const state = useArtifactsStore.getState();
    expect(state.selectedModuleId).toBeNull();
    expect(state.versions).toEqual([]);
    expect(artifactsRepo.fetchVersions).not.toHaveBeenCalled();
  });

  it("remove refreshes the surviving versions from the registry rather than splicing locally", async () => {
    useArtifactsStore.setState({
      moduleIds: ["greeter-provider"],
      selectedModuleId: "greeter-provider",
      versions: [version("greeter-provider", "1.0.0"), version("greeter-provider", "1.1.0")],
    });
    vi.mocked(artifactsRepo.remove).mockResolvedValueOnce(undefined);
    vi.mocked(artifactsRepo.fetchCatalog).mockResolvedValueOnce(["greeter-provider"]);
    vi.mocked(artifactsRepo.fetchVersions).mockResolvedValueOnce([
      version("greeter-provider", "1.1.0"),
    ]);

    await useArtifactsStore.getState().remove("greeter-provider", "1.0.0");

    const state = useArtifactsStore.getState();
    expect(artifactsRepo.remove).toHaveBeenCalledWith("greeter-provider", "1.0.0");
    expect(state.versions.map((v) => v.version)).toEqual(["1.1.0"]);
    expect(state.selectedModuleId).toBe("greeter-provider");
  });

  it("removing a module's last version clears the selection along with the catalog entry", async () => {
    useArtifactsStore.setState({
      moduleIds: ["greeter-provider", "edge-router"],
      selectedModuleId: "edge-router",
      versions: [version("edge-router", "0.9.0")],
    });
    vi.mocked(artifactsRepo.remove).mockResolvedValueOnce(undefined);
    vi.mocked(artifactsRepo.fetchCatalog).mockResolvedValueOnce(["greeter-provider"]);

    await useArtifactsStore.getState().remove("edge-router", "0.9.0");

    const state = useArtifactsStore.getState();
    expect(state.moduleIds).toEqual(["greeter-provider"]);
    expect(state.selectedModuleId).toBeNull();
    expect(state.versions).toEqual([]);
    expect(artifactsRepo.fetchVersions).not.toHaveBeenCalled();
  });

  it("remove surfaces a rejection as store.error and rethrows so the caller can toast it", async () => {
    useArtifactsStore.setState({
      moduleIds: ["greeter-provider"],
      selectedModuleId: "greeter-provider",
      versions: [version("greeter-provider", "1.0.0")],
    });
    vi.mocked(artifactsRepo.remove).mockRejectedValueOnce(new Error("forbidden"));

    await expect(useArtifactsStore.getState().remove("greeter-provider", "1.0.0")).rejects.toThrow(
      "forbidden",
    );

    const state = useArtifactsStore.getState();
    expect(state.error).toBe("forbidden");
    // A refused delete must leave the list exactly as it was -- nothing was removed server-side.
    expect(state.versions.map((v) => v.version)).toEqual(["1.0.0"]);
    expect(artifactsRepo.fetchCatalog).not.toHaveBeenCalled();
  });
});
