import type { Deployment, DeploymentSpecInput, Page } from "@/types";
import { addDeployment, deployments, removeDeployment } from "./fixture";
import { delay, paginate } from "./util";

export interface DeploymentsSummary {
  total: number;
  unplacedInstances: number;
  quotaViolating: number;
  recent: Deployment[];
}

export interface DeploymentsRepository {
  fetchPage(args: { cursor: string | null; pageSize: number }): Promise<Page<Deployment>>;
  fetchOne(name: string): Promise<Deployment>;
  fetchSummary(): Promise<DeploymentsSummary>;
  create(spec: DeploymentSpecInput): Promise<Deployment>;
  remove(name: string, tenantId?: string | null): Promise<void>;
}

export class MockDeploymentsRepository implements DeploymentsRepository {
  async fetchPage({ cursor, pageSize }: { cursor: string | null; pageSize: number }) {
    return delay(paginate(deployments, cursor, pageSize));
  }
  async fetchOne(name: string) {
    const d = deployments.find((x) => x.spec.name === name);
    if (!d) throw new Error(`Deployment not found: ${name}`);
    return delay(d);
  }
  async fetchSummary() {
    const summary: DeploymentsSummary = {
      total: deployments.length,
      unplacedInstances: deployments.reduce((s, d) => s + d.unplacedCount, 0),
      quotaViolating: deployments.filter((d) => d.quotaViolating).length,
      recent: deployments.slice(0, 8),
    };
    return delay(summary);
  }
  async create(spec: DeploymentSpecInput) {
    const d: Deployment = {
      spec,
      instances: [],
      unplacedCount: spec.replicas,
      quotaViolating: false,
    };
    addDeployment(d);
    return delay(d);
  }
  async remove(name: string, _tenantId?: string | null) {
    // Fixture data is keyed by bare name only -- tenantId is accepted for interface parity with
    // HttpDeploymentsRepository but unused here.
    removeDeployment(name);
    return delay(undefined);
  }
}
