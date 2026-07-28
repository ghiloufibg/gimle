import type { ModuleInstance, Page } from "@/types";
import type { InstancesFilter, InstancesRepository } from "@/repositories/instances";
import type { HttpDeploymentsRepository } from "./deployments";

function flatten(deployments: Awaited<ReturnType<HttpDeploymentsRepository["all"]>>): ModuleInstance[] {
  const rows: ModuleInstance[] = [];
  for (const d of deployments) {
    for (const inst of d.instances) {
      rows.push({
        deploymentName: d.spec.name,
        instanceIndex: inst.instanceIndex,
        moduleId: d.spec.moduleId,
        artifactPath: d.spec.artifactPath,
        tenantId: d.spec.tenantId,
        nodeId: inst.nodeId,
        lifecycleState: inst.observation.lifecycleState,
        alive: inst.observation.alive,
        ready: inst.observation.ready,
        requestRatePerSecond: inst.observation.requestRatePerSecond,
        queueDepth: inst.observation.queueDepth,
        cpuMillicoresUsed: inst.observation.cpuMillicoresUsed,
        memoryBytesUsed: inst.observation.memoryBytesUsed,
      });
    }
  }
  return rows;
}

function applyFilter(rows: ModuleInstance[], f?: InstancesFilter): ModuleInstance[] {
  if (!f) return rows;
  return rows.filter((r) => {
    if (f.deployment && !r.deploymentName.includes(f.deployment)) return false;
    if (f.nodeId && r.nodeId !== f.nodeId) return false;
    if (f.tenantId && r.tenantId !== f.tenantId) return false;
    if (f.lifecycle && r.lifecycleState !== f.lifecycle) return false;
    return true;
  });
}

/** No dedicated endpoint exists (§4a) -- flattens the SAME cached array HttpDeploymentsRepository
 * holds, rather than a second independent GET /deployments call that could race and disagree. */
export class HttpInstancesRepository implements InstancesRepository {
  constructor(private readonly deploymentsRepo: HttpDeploymentsRepository) {}

  async fetchPage({
    cursor,
    pageSize,
    filter,
  }: {
    cursor: string | null;
    pageSize: number;
    filter?: InstancesFilter;
  }): Promise<Page<ModuleInstance>> {
    const deployments = await this.deploymentsRepo.all(cursor === null);
    const rows = applyFilter(flatten(deployments), filter);
    const start = cursor ? parseInt(cursor, 10) || 0 : 0;
    const end = Math.min(start + pageSize, rows.length);
    return { items: rows.slice(start, end), nextCursor: end < rows.length ? String(end) : null };
  }

  async fetchOne(deploymentName: string, instanceIndex: number): Promise<ModuleInstance> {
    const find = (deployments: Awaited<ReturnType<typeof this.deploymentsRepo.all>>) =>
      flatten(deployments).find(
        (r) => r.deploymentName === deploymentName && r.instanceIndex === instanceIndex,
      );

    const row = find(await this.deploymentsRepo.all(false));
    if (row) return row;
    // Not in the cached snapshot (e.g. the deployment landed after the cache was populated) -- one
    // forced refresh before giving up, matching the mock's "throw if truly absent" contract.
    const found = find(await this.deploymentsRepo.all(true));
    if (!found) throw new Error(`Instance not found: ${deploymentName}/${instanceIndex}`);
    return found;
  }
}
