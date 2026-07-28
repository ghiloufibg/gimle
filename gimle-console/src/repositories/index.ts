// COMPOSITION ROOT — swap Mock* for real HTTP implementations here to wire a backend.
import { HttpDeploymentsRepository } from "./http/deployments";
import { HttpInstancesRepository } from "./http/instances";
import { HttpNodesRepository } from "./http/nodes";
import { HttpTenantsRepository } from "./http/tenants";
import { HttpConfigRepository } from "./http/config";
import { MockLogsRepository } from "./logs";

export const deploymentsRepo = new HttpDeploymentsRepository();
export const instancesRepo = new HttpInstancesRepository(deploymentsRepo);
export const nodesRepo = new HttpNodesRepository();
export const tenantsRepo = new HttpTenantsRepository();
export const configRepo = new HttpConfigRepository();
// Logs has no real backend yet (log-explorer-design.md §6 is still design-only) -- stays mock.
export const logsRepo = new MockLogsRepository();
