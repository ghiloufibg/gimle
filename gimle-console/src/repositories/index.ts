// COMPOSITION ROOT — swap Mock* for real HTTP implementations here to wire a backend.
import { MockDeploymentsRepository } from "./deployments";
import { MockInstancesRepository } from "./instances";
import { MockNodesRepository } from "./nodes";
import { MockTenantsRepository } from "./tenants";
import { MockConfigRepository } from "./config";
import { MockLogsRepository } from "./logs";

export const deploymentsRepo = new MockDeploymentsRepository();
export const instancesRepo = new MockInstancesRepository();
export const nodesRepo = new MockNodesRepository();
export const tenantsRepo = new MockTenantsRepository();
export const configRepo = new MockConfigRepository();
export const logsRepo = new MockLogsRepository();
