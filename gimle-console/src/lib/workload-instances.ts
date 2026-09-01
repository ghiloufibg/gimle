import type { DaemonSet, Deployment, ModuleInstance, StatefulSet } from "@/types";

/**
 * Flattens a workload detail response's own instance list into the shared {@link ModuleInstance}
 * row shape, so all three workload detail screens render through the one paginated instances table
 * the Nodes and Instances screens already use instead of each hand-rolling an unbounded one.
 *
 * The rows come from the workload's own payload rather than from a filtered cluster-wide instance
 * query: that list is already exactly this workload's instances, with no chance of a name-prefix
 * filter pulling in a differently-named neighbour's.
 */

/** How many rows a workload detail page shows before the table offers "Load more". */
export const WORKLOAD_INSTANCE_PAGE = 25;

export function deploymentInstanceRows(d: Deployment): ModuleInstance[] {
  return d.instances.map((i) => ({
    deploymentName: d.spec.name,
    instanceIndex: i.instanceIndex,
    moduleId: d.spec.moduleId,
    artifactPath: d.spec.artifactPath,
    tenantId: d.spec.tenantId,
    nodeId: i.nodeId,
    ...i.observation,
  }));
}

/**
 * A DaemonSet instance is identified by the node it runs on, not by an index -- the manifest kind
 * has exactly one per node -- so every row reports index 0, matching the instance index the log API
 * itself addresses a DaemonSet instance by.
 */
export function daemonSetInstanceRows(d: DaemonSet): ModuleInstance[] {
  return d.instances.map((i) => ({
    deploymentName: d.spec.name,
    instanceIndex: 0,
    moduleId: d.spec.moduleId,
    artifactPath: d.spec.artifactPath,
    tenantId: d.spec.tenantId,
    nodeId: i.nodeId,
    ...i.observation,
  }));
}

export function statefulSetInstanceRows(s: StatefulSet): ModuleInstance[] {
  return s.instances.map((i) => ({
    deploymentName: s.spec.name,
    instanceIndex: i.instanceIndex,
    moduleId: s.spec.moduleId,
    artifactPath: s.spec.artifactPath,
    tenantId: s.spec.tenantId,
    nodeId: i.nodeId,
    ...i.observation,
  }));
}

/**
 * The bounded slice a detail page actually renders, plus whether anything is left. The whole list
 * is already in memory (it arrived with the workload), so this caps what the DOM has to carry --
 * a Deployment autoscaled to hundreds of replicas is exactly when the page matters most and is
 * exactly when one unbounded table stops being usable.
 */
export function instanceWindow<T>(
  rows: readonly T[],
  visibleCount: number,
): { visible: T[]; hasMore: boolean } {
  const capped = Math.max(0, Math.min(visibleCount, rows.length));
  return { visible: rows.slice(0, capped), hasMore: capped < rows.length };
}
