import type { Blueprint } from "./blueprint";
import { WORKLOAD_KINDS } from "./blueprint";
import type { RunLogLine } from "@/repositories";

/** One artifact the backend reported as pushed, parsed out of the run console. */
export interface PushedArtifact {
  /** `com.gimle.examples.hello@1.0.0` as printed by the backend. */
  moduleId: string;
  version: string;
  path: string;
}

const PREFIX = "pushed artifact ";

/**
 * Derives the artifact list from the run log, the same deterministic way the
 * step timeline is derived: a line starting `pushed artifact ` names one push,
 * printed as `<moduleId>@<version> from <path>`.
 */
export function artifactsFromLog(log: RunLogLine[]): PushedArtifact[] {
  const out: PushedArtifact[] = [];
  const seen = new Set<string>();
  for (const line of log) {
    // The backend stamps every line "[<instant>] ", so the marker is looked for anywhere in the
    // line rather than at its start -- anchoring at the start matched nothing at all, and the
    // panel stayed on its empty state for the whole life of a running cluster.
    const text = line.text;
    const marker = text.toLowerCase().indexOf(PREFIX);
    if (marker < 0) continue;
    const rest = text.slice(marker + PREFIX.length).trim();
    const [coords, ...pathParts] = rest.split(" from ");
    if (!coords) continue;
    const at = coords.lastIndexOf("@");
    const moduleId = at > 0 ? coords.slice(0, at) : coords;
    const version = at > 0 ? coords.slice(at + 1) : "";
    const path = pathParts.join(" from ").trim();
    const key = `${moduleId}@${version}|${path}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ moduleId, version, path });
  }
  return out;
}

/** True when the blueprint declares at least one workload sourced from a jar. */
export function hasJarWorkloads(blueprint: Blueprint | null | undefined): boolean {
  if (!blueprint) return false;
  return blueprint.nodes.some((n) => {
    if (!WORKLOAD_KINDS.includes(n.kind as never)) return false;
    const artifact = (n.data as { artifact?: { source?: string } } | undefined)?.artifact;
    return artifact?.source === "jar";
  });
}

/** Secret keys declared on the canvas, in canvas order, deduplicated. */
export function secretKeys(blueprint: Blueprint | null | undefined): string[] {
  if (!blueprint) return [];
  const keys: string[] = [];
  for (const n of blueprint.nodes) {
    if (n.kind !== "secret") continue;
    const key = (n.data as { key?: string } | undefined)?.key?.trim();
    if (key && !keys.includes(key)) keys.push(key);
  }
  return keys;
}
