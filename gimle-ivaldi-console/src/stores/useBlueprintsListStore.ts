import { create } from "zustand";

import { createBlueprint, uid, type Blueprint } from "@/lib/blueprint";
import { blueprintsRepository, type BlueprintSummary } from "@/repositories";

interface ListState {
  /** Summaries as returned by the list endpoint: no counts. */
  blueprints: BlueprintSummary[];
  /** Full bodies, filled in lazily in the background for the count columns. */
  details: Record<string, Blueprint>;
  loading: boolean;
  error: string | null;
  /** Set alongside `error` so a caller can tell a delete refusal from a plain load failure. */
  errorTitle: string | null;
  refresh: () => Promise<void>;
  loadDetails: () => Promise<void>;
  create: (name: string, options?: { empty?: boolean }) => Promise<BlueprintSummary>;
  rename: (id: string, name: string) => Promise<void>;
  remove: (id: string) => Promise<void>;
  duplicate: (id: string) => Promise<BlueprintSummary | null>;
  importBlueprint: (blueprint: Blueprint) => Promise<BlueprintSummary>;
}

/** Keeps list names distinct so a copy or a re-import is identifiable. */
function uniqueName(taken: string[], base: string): string {
  if (!taken.includes(base)) return base;
  for (let i = 2; i < 1000; i++) {
    const candidate = `${base}-${i}`;
    if (!taken.includes(candidate)) return candidate;
  }
  return `${base}-${Date.now()}`;
}

/** Every write stamps updatedAt: the backend echoes it back verbatim. */
function stamped(bp: Blueprint): Blueprint {
  return { ...bp, version: bp.version || "1.0.0", updatedAt: new Date().toISOString() };
}

export const useBlueprintsListStore = create<ListState>((set, get) => ({
  blueprints: [],
  details: {},
  loading: false,
  error: null,
  errorTitle: null,

  refresh: async () => {
    set({ loading: true, error: null, errorTitle: null });
    try {
      const blueprints = await blueprintsRepository.list();
      set({ blueprints, loading: false });
      void get().loadDetails();
    } catch (e) {
      set({
        loading: false,
        error: e instanceof Error ? e.message : String(e),
        errorTitle: "Couldn't load blueprints",
      });
    }
  },

  loadDetails: async () => {
    for (const summary of get().blueprints) {
      if (get().details[summary.id]) continue;
      const full = await blueprintsRepository.get(summary.id);
      if (full) set((s) => ({ details: { ...s.details, [summary.id]: full } }));
    }
  },

  create: async (name, options) => {
    const created = await blueprintsRepository.create(stamped(createBlueprint(name, options)));
    await get().refresh();
    return created;
  },

  rename: async (id, name) => {
    const source = await blueprintsRepository.get(id);
    if (!source) return;
    await blueprintsRepository.save(stamped({ ...source, name }));
    await get().refresh();
  },

  remove: async (id) => {
    try {
      await blueprintsRepository.delete(id);
    } catch (e) {
      // A run tracked against this blueprint refuses the delete (409) -- the route's own
      // client-side pre-check only knows about a run *this* session started, so a run tracked by
      // another tab or process still has to surface here rather than as an unhandled rejection
      // with nothing shown to the operator at all.
      set({
        error: e instanceof Error ? e.message : String(e),
        errorTitle: "Couldn't delete blueprint",
      });
      return;
    }
    set((s) => {
      const details = { ...s.details };
      delete details[id];
      return { details, error: null, errorTitle: null };
    });
    await get().refresh();
  },

  duplicate: async (id) => {
    const source = await blueprintsRepository.get(id);
    if (!source) return null;
    const taken = get().blueprints.map((b) => b.name);
    const copy = await blueprintsRepository.create(
      // A fresh id, not the source's own: create() now refuses a request naming an id already on
      // disk rather than silently minting an unrelated one, and the source's own id -- the one
      // this copy is a copy *of* -- always collides.
      stamped({ ...source, id: uid("bp"), name: uniqueName(taken, `${source.name}-copy`) }),
    );
    await get().refresh();
    return copy;
  },

  importBlueprint: async (blueprint) => {
    const taken = get().blueprints.map((b) => b.name);
    const saved = await blueprintsRepository.create(
      stamped({
        ...blueprint,
        // A fresh id, not whatever the imported document happens to carry: re-importing the same
        // exported zip a second time -- a retry, or just re-opening an old backup -- named the
        // same id both times, which create() now refuses rather than silently minting past it.
        id: uid("bp"),
        name: taken.includes(blueprint.name)
          ? uniqueName(taken, `${blueprint.name}-imported`)
          : blueprint.name,
      }),
    );
    await get().refresh();
    return saved;
  },
}));
