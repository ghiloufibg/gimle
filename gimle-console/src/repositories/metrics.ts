import type { DeploymentMetricsRollup } from "@/types";
import { delay } from "./util";

/**
 * The live per-deployment rollup (`GET /metrics`), distinct from `metricsHistory.ts`, which reads
 * time-series lines back out of Muninn. This one is a point-in-time snapshot derived from the
 * node heartbeats the control plane currently holds -- there is no query window to pass it.
 */
export interface MetricsRepository {
  fetchRollup(): Promise<DeploymentMetricsRollup[]>;
}

const mockRollup: DeploymentMetricsRollup[] = [
  {
    tenantId: "acme",
    deploymentName: "greeter-provider",
    instanceCount: 2,
    avgRequestRatePerSecond: 42.5,
    avgErrorRatePerSecond: 0.25,
  },
  {
    // Nothing placed yet: the averages are a real 0, not "unknown".
    tenantId: null,
    deploymentName: "greeter-consumer",
    instanceCount: 0,
    avgRequestRatePerSecond: 0,
    avgErrorRatePerSecond: 0,
  },
];

export class MockMetricsRepository implements MetricsRepository {
  async fetchRollup(): Promise<DeploymentMetricsRollup[]> {
    return delay(mockRollup.map((r) => ({ ...r })));
  }
}
