import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  authzVocabularyRepo: { fetch: vi.fn() },
}));

import { authzVocabularyRepo } from "@/repositories";
import { RESOURCE_KINDS, VERBS } from "@/types";
import { useAuthzVocabularyStore } from "./useAuthzVocabularyStore";

describe("useAuthzVocabularyStore", () => {
  beforeEach(() => {
    useAuthzVocabularyStore.setState({
      resourceKinds: [...RESOURCE_KINDS],
      verbs: [...VERBS],
      live: false,
      loading: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("starts on the bundled fallback so the editor is usable before anything is fetched", () => {
    const state = useAuthzVocabularyStore.getState();
    expect(state.resourceKinds).toEqual([...RESOURCE_KINDS]);
    expect(state.live).toBe(false);
  });

  it("replaces the fallback with the control plane's own enum, extra kinds included", async () => {
    vi.mocked(authzVocabularyRepo.fetch).mockResolvedValueOnce({
      resourceKinds: ["DEPLOYMENT", "SOMETHING_NEW"],
      verbs: ["READ"],
    });

    await useAuthzVocabularyStore.getState().load();

    const state = useAuthzVocabularyStore.getState();
    expect(state.resourceKinds).toEqual(["DEPLOYMENT", "SOMETHING_NEW"]);
    expect(state.verbs).toEqual(["READ"]);
    expect(state.live).toBe(true);
  });

  it("keeps the fallback and stays silent when the endpoint cannot be read", async () => {
    vi.mocked(authzVocabularyRepo.fetch).mockRejectedValueOnce(new Error("404"));

    await useAuthzVocabularyStore.getState().load();

    const state = useAuthzVocabularyStore.getState();
    expect(state.resourceKinds).toEqual([...RESOURCE_KINDS]);
    expect(state.live).toBe(false);
    expect(state.loading).toBe(false);
  });

  it("keeps the fallback when the endpoint answers with an empty vocabulary", async () => {
    vi.mocked(authzVocabularyRepo.fetch).mockResolvedValueOnce({ resourceKinds: [], verbs: [] });

    await useAuthzVocabularyStore.getState().load();

    expect(useAuthzVocabularyStore.getState().resourceKinds).toEqual([...RESOURCE_KINDS]);
    expect(useAuthzVocabularyStore.getState().live).toBe(false);
  });

  it("fetches once: every later mount of the editor reuses the loaded vocabulary", async () => {
    vi.mocked(authzVocabularyRepo.fetch).mockResolvedValue({
      resourceKinds: ["DEPLOYMENT"],
      verbs: ["READ"],
    });

    await useAuthzVocabularyStore.getState().load();
    await useAuthzVocabularyStore.getState().load();

    expect(authzVocabularyRepo.fetch).toHaveBeenCalledTimes(1);
  });

  it("retries after a failure, since nothing live was ever loaded", async () => {
    vi.mocked(authzVocabularyRepo.fetch).mockRejectedValueOnce(new Error("unreachable"));
    vi.mocked(authzVocabularyRepo.fetch).mockResolvedValueOnce({
      resourceKinds: ["DEPLOYMENT"],
      verbs: ["READ"],
    });

    await useAuthzVocabularyStore.getState().load();
    await useAuthzVocabularyStore.getState().load();

    expect(authzVocabularyRepo.fetch).toHaveBeenCalledTimes(2);
    expect(useAuthzVocabularyStore.getState().live).toBe(true);
  });
});
