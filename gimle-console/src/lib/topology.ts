import type { Deployment, DeploymentInstance } from "@/types";

/**
 * The Topology screen's per-replica badge slots for a deployment: one entry per badge, left to
 * right. A placed slot carries the instance it represents; a trailing `null` is an unplaced
 * replica (the manifest asks for more than are currently placed).
 *
 * Sorted by each instance's own `instanceIndex`, never by the response array's own order -- the
 * control plane can (and does) return instances out of ascending-index order, and a badge must
 * always read the instance it actually represents, not whichever one happens to arrive at that
 * array position.
 */
export function replicaBadgeSlots(d: Deployment): Array<DeploymentInstance | null> {
  const sorted = [...d.instances].sort((a, b) => a.instanceIndex - b.instanceIndex);
  const total = Math.max(sorted.length, d.spec.replicas);
  const slots: Array<DeploymentInstance | null> = [...sorted];
  while (slots.length < total) slots.push(null);
  return slots;
}
