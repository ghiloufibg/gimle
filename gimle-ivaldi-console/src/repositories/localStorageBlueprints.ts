import type { Blueprint } from "@/lib/blueprint";
import { sampleBlueprints } from "@/lib/samples";

import type { BlueprintSummary, BlueprintsRepository } from "./contracts";

const KEY = "ivaldi.blueprints.v1";

function read(): Blueprint[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(KEY);
    if (raw === null) {
      const seeded = sampleBlueprints();
      window.localStorage.setItem(KEY, JSON.stringify(seeded));
      return seeded;
    }
    const parsed = JSON.parse(raw) as Blueprint[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function write(list: Blueprint[]): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(KEY, JSON.stringify(list));
}

function slugify(name: string): string {
  return (
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "") || "blueprint"
  );
}

function summary(bp: Blueprint): BlueprintSummary {
  return { id: bp.id, name: bp.name, version: bp.version, updatedAt: bp.updatedAt };
}

/** Mock store: mirrors the backend's semantics (server-minted ids, summaries). */
export class MockBlueprintsRepository implements BlueprintsRepository {
  readonly mode = "mock" as const;

  async list(): Promise<BlueprintSummary[]> {
    return read()
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .map(summary);
  }

  async get(id: string): Promise<Blueprint | undefined> {
    return read().find((b) => b.id === id);
  }

  async create(blueprint: Blueprint): Promise<BlueprintSummary> {
    const list = read();
    let id = slugify(blueprint.name);
    if (list.some((b) => b.id === id)) id = `${id}-${Math.random().toString(36).slice(2, 6)}`;
    const next: Blueprint = { ...blueprint, id };
    list.push(next);
    write(list);
    return summary(next);
  }

  async save(blueprint: Blueprint): Promise<BlueprintSummary> {
    const list = read();
    const idx = list.findIndex((b) => b.id === blueprint.id);
    if (idx >= 0) list[idx] = blueprint;
    else list.push(blueprint);
    write(list);
    return summary(blueprint);
  }

  async delete(id: string): Promise<void> {
    write(read().filter((b) => b.id !== id));
  }
}

/** Kept as an alias so existing imports keep working. */
export const LocalStorageBlueprintsRepository = MockBlueprintsRepository;
