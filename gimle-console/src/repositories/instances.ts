import type { LifecycleState, ModuleInstance, Page } from "@/types";
import { deployments } from "./fixture";
import { delay, paginate } from "./util";

export interface InstancesFilter {
  deployment?: string;
  nodeId?: string;
  tenantId?: string;
  lifecycle?: LifecycleState;
}

export interface InstancesRepository {
  fetchPage(args: {
    cursor: string | null;
    pageSize: number;
    filter?: InstancesFilter;
  }): Promise<Page<ModuleInstance>>;
  fetchOne(deploymentName: string, instanceIndex: number): Promise<ModuleInstance>;
}

function flatten(): ModuleInstance[] {
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

export class MockInstancesRepository implements InstancesRepository {
  async fetchPage({
    cursor,
    pageSize,
    filter,
  }: {
    cursor: string | null;
    pageSize: number;
    filter?: InstancesFilter;
  }) {
    return delay(paginate(applyFilter(flatten(), filter), cursor, pageSize));
  }
  async fetchOne(deploymentName: string, instanceIndex: number) {
    const rows = flatten();
    const row = rows.find(
      (r) => r.deploymentName === deploymentName && r.instanceIndex === instanceIndex,
    );
    if (!row) throw new Error(`Instance not found: ${deploymentName}/${instanceIndex}`);
    return delay(row);
  }
}
