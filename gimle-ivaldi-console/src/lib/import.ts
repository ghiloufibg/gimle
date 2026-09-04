import { unzipSync, strFromU8 } from "fflate";

import {
  APP_KINDS,
  EDGE_LABELS,
  PLATFORM_KINDS,
  defaultDataFor,
  type Blueprint,
  type BlueprintEdge,
  type BlueprintNode,
  type EdgeKind,
  type NodeKind,
} from "./blueprint";

const KINDS = new Set<string>([...PLATFORM_KINDS, ...APP_KINDS]);
const EDGE_KINDS = new Set<string>(Object.keys(EDGE_LABELS));

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

/**
 * Validates and normalises an imported document, so a malformed file is refused at the door
 * with a message naming what is wrong rather than persisted as a blueprint whose designer then
 * throws on open -- a state the list offers no way out of but deleting the blueprint.
 */
export function normaliseBlueprint(value: unknown): Blueprint {
  if (!isRecord(value)) throw new Error("File does not contain a blueprint object.");
  if (typeof value.name !== "string" || !value.name.trim())
    throw new Error("Blueprint is missing a name.");
  if (!Array.isArray(value.nodes)) throw new Error("Blueprint is missing its nodes list.");
  if (!Array.isArray(value.edges)) throw new Error("Blueprint is missing its edges list.");

  const nodes: BlueprintNode[] = value.nodes.map((raw, i) => {
    if (!isRecord(raw)) throw new Error(`Node #${i + 1} is not an object.`);
    if (typeof raw.id !== "string" || !raw.id) throw new Error(`Node #${i + 1} has no id.`);
    if (typeof raw.kind !== "string" || !KINDS.has(raw.kind))
      throw new Error(`Node "${raw.id}" has an unknown kind "${String(raw.kind)}".`);
    const kind = raw.kind as NodeKind;
    const pos = isRecord(raw.position) ? raw.position : {};
    if (!isRecord(raw.data)) throw new Error(`Node "${raw.id}" has no data.`);
    return {
      id: raw.id,
      kind,
      position: {
        x: typeof pos.x === "number" ? pos.x : 0,
        y: typeof pos.y === "number" ? pos.y : 0,
      },
      // Any field the document omits falls back to that kind's own default, so an older or
      // hand-edited file opens instead of leaving the inspector reading undefined.
      data: { ...defaultDataFor(kind), ...raw.data } as BlueprintNode["data"],
    };
  });

  const ids = new Set(nodes.map((n) => n.id));
  const edges: BlueprintEdge[] = value.edges.map((raw, i) => {
    if (!isRecord(raw)) throw new Error(`Edge #${i + 1} is not an object.`);
    if (typeof raw.kind !== "string" || !EDGE_KINDS.has(raw.kind))
      throw new Error(`Edge #${i + 1} has an unknown kind "${String(raw.kind)}".`);
    if (typeof raw.source !== "string" || typeof raw.target !== "string")
      throw new Error(`Edge #${i + 1} is missing a source or target.`);
    if (!ids.has(raw.source) || !ids.has(raw.target))
      throw new Error(`Edge #${i + 1} points at a node that is not in the file.`);
    return {
      id: typeof raw.id === "string" && raw.id ? raw.id : `edge-${i + 1}`,
      kind: raw.kind as EdgeKind,
      source: raw.source,
      target: raw.target,
    };
  });

  const runtime = isRecord(value.runtime) ? value.runtime : {};

  return {
    id: typeof value.id === "string" ? value.id : "",
    name: value.name,
    version: typeof value.version === "string" && value.version ? value.version : "1.0.0",
    transport: value.transport === "mtls" ? "mtls" : "plaintext",
    ...(typeof value.tlsMaterialDir === "string" ? { tlsMaterialDir: value.tlsMaterialDir } : {}),
    runtime: {
      dataRoot: typeof runtime.dataRoot === "string" ? runtime.dataRoot : "/var/lib/gimle",
      ...(typeof runtime.classpath === "string" ? { classpath: runtime.classpath } : {}),
    },
    nodes,
    edges,
    updatedAt: typeof value.updatedAt === "string" ? value.updatedAt : new Date().toISOString(),
  };
}

export async function readBlueprintFile(file: File): Promise<Blueprint> {
  if (file.name.endsWith(".json")) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(await file.text());
    } catch {
      throw new Error("File is not valid JSON.");
    }
    return normaliseBlueprint(parsed);
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  const entries = unzipSync(bytes);
  const raw = entries["ivaldi.blueprint.json"];
  if (!raw) throw new Error("Zip does not contain ivaldi.blueprint.json.");
  let parsed: unknown;
  try {
    parsed = JSON.parse(strFromU8(raw));
  } catch {
    throw new Error("Blueprint JSON in the zip is not valid JSON.");
  }
  return normaliseBlueprint(parsed);
}
