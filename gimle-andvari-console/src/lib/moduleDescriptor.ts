import { readZipEntry } from "@/lib/zip";

const DESCRIPTOR_ENTRY = "META-INF/gimle/gimle-module.yaml";

export interface ModuleCoordinate {
  moduleId: string;
  version: string;
}

/**
 * Extracts `{moduleId, version}` from a jar's own bundled `gimle-module.yaml`, mirroring
 * `ModuleArtifactReader`/`gimle artifact push`'s own coordinate derivation: the coordinate an
 * artifact is stored under and the identity it declares for itself must never be able to drift
 * apart, since that's what makes a coordinate-only deployment manifest's `module: {name, version}`
 * reference trustworthy. Only `name`/`version` are read here -- a full descriptor parse
 * (isolation tier, resources, exports, ...) belongs to the worker's own `ModuleDescriptorParser`,
 * not this client-side check, and Andvari itself deliberately never parses the descriptor at all.
 *
 * Returns `null` for a vessel jar (no bundled descriptor) or any jar this can't parse -- both fall
 * back to the operator typing a coordinate by hand, the same as `gimle artifact push --vessel`
 * does server-side for a jar with nothing to derive a coordinate from.
 */
export async function readModuleCoordinate(file: Blob): Promise<ModuleCoordinate | null> {
  let bytes: Uint8Array | null;
  try {
    bytes = await readZipEntry(file, DESCRIPTOR_ENTRY);
  } catch {
    return null;
  }
  if (!bytes) return null;

  const yaml = new TextDecoder("utf-8").decode(bytes);
  const moduleId = topLevelScalar(yaml, "name");
  const version = topLevelScalar(yaml, "version");
  if (!moduleId || !version) return null;
  return { moduleId, version };
}

// Reads a top-level (column-zero) `key: value` scalar out of the descriptor's YAML. Deliberately
// not a full YAML parser -- the worker's own ModuleDescriptorParser already owns real parsing and
// validation of every field; this only ever needs these two, always-scalar, always-root-level
// ones to derive a coordinate the same way the CLI does.
function topLevelScalar(yamlText: string, key: string): string | null {
  const match = new RegExp(`^${key}:[ \\t]*(.+?)[ \\t]*$`, "m").exec(yamlText);
  if (!match) return null;
  let value = match[1];

  const quoted = /^"([^"]*)"$/.exec(value) ?? /^'([^']*)'$/.exec(value);
  if (quoted) return quoted[1];

  // An unquoted scalar's trailing `# comment` isn't part of the value.
  const commentIndex = value.indexOf(" #");
  if (commentIndex >= 0) {
    value = value.slice(0, commentIndex).trimEnd();
  }
  return value || null;
}
