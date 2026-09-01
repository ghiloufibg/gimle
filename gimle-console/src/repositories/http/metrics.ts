import type { DeploymentMetricsRollup } from "@/types";
import type { MetricsRepository } from "@/repositories/metrics";
import { requestJson } from "./apiClient";

/**
 * `GET /metrics` -- one row per deployment the caller may read, flat array, no pagination and no
 * filter parameters of any kind. The tenant narrowing happens server-side from the caller's own
 * RBAC grants, so there is nothing to pass here.
 */
export class HttpMetricsRepository implements MetricsRepository {
  async fetchRollup(): Promise<DeploymentMetricsRollup[]> {
    return requestJson<DeploymentMetricsRollup[]>("GET", "/metrics");
  }
}
