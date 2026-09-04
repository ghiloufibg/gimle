import type { Blueprint } from "@/lib/blueprint";

import { jsonBody, requestJson, requestOk } from "./apiClient";
import type { BlueprintSummary, BlueprintsRepository } from "./contracts";

/** Wire shape returned by list/create/update. */
interface RawBlueprintSummary {
  id?: string;
  name?: string;
  version?: string;
  updatedAt?: string;
}

function mapSummary(raw: RawBlueprintSummary): BlueprintSummary {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "(unnamed)",
    version: raw.version ?? "1.0.0",
    updatedAt: raw.updatedAt ?? "",
  };
}

/**
 * Talks to the real gimle-ivaldi backend over same-origin /api paths.
 * Swap this in for the mock in repositories/index.ts once the backend is
 * reachable from the browser serving this app.
 */
export class HttpBlueprintsRepository implements BlueprintsRepository {
  readonly mode = "http" as const;

  async list(): Promise<BlueprintSummary[]> {
    const raw = await requestJson<RawBlueprintSummary[]>("/api/blueprints");
    return (Array.isArray(raw) ? raw : []).map(mapSummary);
  }

  async get(id: string): Promise<Blueprint | undefined> {
    try {
      return await requestJson<Blueprint>(`/api/blueprints/${encodeURIComponent(id)}`);
    } catch {
      return undefined;
    }
  }

  /** POST: the server mints the id from the name; read it from the response. */
  async create(blueprint: Blueprint): Promise<BlueprintSummary> {
    const raw = await requestJson<RawBlueprintSummary>("/api/blueprints", {
      method: "POST",
      body: jsonBody(blueprint),
    });
    return mapSummary(raw);
  }

  /** PUT: upsert at a known id. */
  async save(blueprint: Blueprint): Promise<BlueprintSummary> {
    const raw = await requestJson<RawBlueprintSummary>(
      `/api/blueprints/${encodeURIComponent(blueprint.id)}`,
      { method: "PUT", body: jsonBody(blueprint) },
    );
    return mapSummary(raw);
  }

  async delete(id: string): Promise<void> {
    await requestOk(`/api/blueprints/${encodeURIComponent(id)}`, { method: "DELETE" });
  }
}
