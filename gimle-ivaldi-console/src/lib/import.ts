import { unzipSync, strFromU8 } from "fflate";

import type { Blueprint } from "./blueprint";

function isBlueprint(value: unknown): value is Blueprint {
  const v = value as Blueprint;
  return Boolean(v && typeof v.name === "string" && Array.isArray(v.nodes) && Array.isArray(v.edges));
}

export async function readBlueprintFile(file: File): Promise<Blueprint> {
  if (file.name.endsWith(".json")) {
    const parsed: unknown = JSON.parse(await file.text());
    if (!isBlueprint(parsed)) throw new Error("Not an Ivaldi blueprint JSON file.");
    return parsed;
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  const entries = unzipSync(bytes);
  const raw = entries["ivaldi.blueprint.json"];
  if (!raw) throw new Error("Zip does not contain ivaldi.blueprint.json.");
  const parsed: unknown = JSON.parse(strFromU8(raw));
  if (!isBlueprint(parsed)) throw new Error("Blueprint JSON in the zip is malformed.");
  return parsed;
}
