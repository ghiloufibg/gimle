import { create } from "zustand";
import { authzVocabularyRepo } from "@/repositories";
import { vocabularyOptions } from "@/lib/authz-vocabulary";
import { RESOURCE_KINDS, VERBS } from "@/types";

interface State {
  resourceKinds: string[];
  verbs: string[];
  /** True once the control plane's own vocabulary replaced the bundled fallback below. */
  live: boolean;
  loading: boolean;
  load(): Promise<void>;
}

/**
 * Seeded with the bundled fallback rather than empty, so the permission editor is usable on first
 * paint and stays usable if `/authz/vocabulary` never answers -- a picker that renders nothing is
 * strictly worse than one rendering a list that may be a release behind.
 */
export const useAuthzVocabularyStore = create<State>((set, get) => ({
  resourceKinds: [...RESOURCE_KINDS],
  verbs: [...VERBS],
  live: false,
  loading: false,
  async load() {
    if (get().loading || get().live) return;
    set({ loading: true });
    try {
      const served = await authzVocabularyRepo.fetch();
      set({
        resourceKinds: vocabularyOptions(served.resourceKinds, RESOURCE_KINDS),
        verbs: vocabularyOptions(served.verbs, VERBS),
        live: served.resourceKinds.length > 0,
        loading: false,
      });
    } catch {
      // Deliberately silent: the fallback already in state is a complete, workable vocabulary, so
      // an unreachable endpoint is not worth an error banner over a screen that still works.
      set({ loading: false });
    }
  },
}));
