import type { Volume } from "@/types";

/**
 * Pure helpers behind the Volumes screen, kept out of the route module so they can be exercised in
 * this project's node-environment test run rather than through a rendered tree.
 */

/**
 * The `(node, tenant, statefulSet, index, volumeName)` tuple that identifies one row. The tenant
 * belongs in the key: two tenants may each own a `data` volume for the same StatefulSet name and
 * index on the same node, and those are different directories on disk.
 */
export function volumeKey(volume: Volume): string {
  return [
    volume.nodeId,
    volume.tenantId ?? "",
    volume.statefulSet,
    volume.instanceIndex,
    volume.volumeName,
  ].join("/");
}

export interface VolumeState {
  label: string;
  variant: "ok" | "warn" | "bad" | "info" | "muted";
  /** Why this row is or is not destroyable, shown as the cell's tooltip. */
  detail: string;
}

/**
 * `attached` (the store still binds this index to this node) and `inUse` (the node's agent sees a
 * supervised instance holding it right now) answer different questions and can disagree, so the
 * two are reported together rather than collapsed into one flag.
 */
export function volumeState(volume: Volume): VolumeState {
  if (volume.attached && volume.inUse) {
    return {
      label: "in use",
      variant: "ok",
      detail: "Bound to this node and held by a running instance.",
    };
  }
  if (volume.attached) {
    return {
      label: "attached",
      variant: "info",
      detail: "Still bound to this node, but no instance currently holds it.",
    };
  }
  if (volume.inUse) {
    return {
      label: "in use, unbound",
      variant: "warn",
      detail:
        "The store no longer binds this index here, but the node's agent still reports an" +
        " instance holding it. Not destroyable until that instance releases it.",
    };
  }
  return {
    label: "orphaned",
    variant: "warn",
    detail: "Retained after its owner went away. Nothing holds it, so it can be destroyed.",
  };
}

/**
 * A volume may only be destroyed once nothing binds it and nothing holds it. `inUse` alone blocks
 * the action even for a detached volume: the owning agent refuses it anyway, and offering a button
 * that always fails is worse than not offering one.
 */
export function isReclaimable(volume: Volume): boolean {
  return !volume.attached && !volume.inUse;
}

/** Names exactly what a destroy would erase, for the confirmation dialog's own title. */
export function describeVolume(volume: Volume): string {
  const tenant = volume.tenantId === null ? "untenanted" : `tenant ${volume.tenantId}`;
  return (
    `${volume.statefulSet}[${volume.instanceIndex}] · ${volume.volumeName}` +
    ` on node ${volume.nodeId} (${tenant})`
  );
}

/** Case-insensitive substring match across every field an operator would search a volume by. */
export function matchesFilter(volume: Volume, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === "") return true;
  return [
    volume.nodeId,
    volume.tenantId ?? "",
    volume.statefulSet,
    String(volume.instanceIndex),
    volume.volumeName,
    volume.path,
  ].some((field) => field.toLowerCase().includes(q));
}

export function filterVolumes(volumes: Volume[], query: string): Volume[] {
  return volumes.filter((v) => matchesFilter(v, query));
}

export function totalUsedBytes(volumes: Volume[]): number {
  return volumes.reduce((sum, v) => sum + v.usedBytes, 0);
}

export function reclaimableVolumes(volumes: Volume[]): Volume[] {
  return volumes.filter(isReclaimable);
}

/**
 * An empty listing means "none" only when every node answered. With a node unreachable it means
 * "unknown" instead, which is the opposite conclusion for an operator about to decide there is
 * nothing left to reclaim -- so the two cases get different copy.
 */
export function emptyListingMessage(unreachableNodes: string[]): string {
  if (unreachableNodes.length === 0) {
    return "No volumes on any node.";
  }
  return (
    "No volumes reported by the nodes that answered — " +
    `${unreachableNodes.length} node(s) could not be reached, so this may not be all of them.`
  );
}

/** The warning strip's own sentence, naming the nodes whose volumes are missing from the table. */
export function unreachableWarning(unreachableNodes: string[]): string {
  return (
    `${unreachableNodes.length} node(s) could not be reached: ${unreachableNodes.join(", ")}.` +
    " Their volumes are missing from this listing — it is incomplete, not empty."
  );
}
