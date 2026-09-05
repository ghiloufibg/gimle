import { create } from "zustand";

import { createBlueprint, type Blueprint } from "@/lib/blueprint";
import { blueprintsRepository, type BlueprintSummary } from "@/repositories";

interface ListState {
  /** Summaries as returned by the list endpoint: no counts. */
  blueprints: BlueprintSummary[];
  /** Full bodies, filled in lazily in the background for the count columns. */
  details: Record<string, Blueprint>;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  loadDetails: () => Promise<void>;
  create: (name: string, options?: { empty?: boolean }) => Promise<BlueprintSummary>;
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

  refresh: async () => {
    set({ loading: true, error: null });
    try {
      const blueprints = await blueprintsRepository.list();
      set({ blueprints, loading: false });
      void get().loadDetails();
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : String(e) });
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

  remove: async (id) => {
    await blueprintsRepository.delete(id);
    set((s) => {
      const details = { ...s.details };
      delete details[id];
      return { details };
    });
    await get().refresh();
  },

  duplicate: async (id) => {
    const source = await blueprintsRepository.get(id);
    if (!source) return null;
    const taken = get().blueprints.map((b) => b.name);
    const copy = await blueprintsRepository.create(
      stamped({ ...source, name: uniqueName(taken, `${source.name}-copy`) }),
    );
    await get().refresh();
    return copy;
  },

  importBlueprint: async (blueprint) => {
    const taken = get().blueprints.map((b) => b.name);
    const saved = await blueprintsRepository.create(
      stamped({
        ...blueprint,
        name: taken.includes(blueprint.name)
          ? uniqueName(taken, `${blueprint.name}-imported`)
          : blueprint.name,
      }),
    );
    await get().refresh();
    return saved;
  },
}));
