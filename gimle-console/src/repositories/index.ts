// COMPOSITION ROOT — swap Mock* for real HTTP implementations here to wire a backend.
import { HttpDeploymentsRepository } from "./http/deployments";
import { HttpInstancesRepository } from "./http/instances";
import { HttpNodesRepository } from "./http/nodes";
import { HttpTenantsRepository } from "./http/tenants";
import { HttpConfigRepository } from "./http/config";
import { HttpSecretsRepository } from "./http/secrets";
import { HttpLogsRepository } from "./http/logs";
import { HttpAuthRepository } from "./http/auth";

export const deploymentsRepo = new HttpDeploymentsRepository();
export const instancesRepo = new HttpInstancesRepository(deploymentsRepo);
export const nodesRepo = new HttpNodesRepository();
export const tenantsRepo = new HttpTenantsRepository();
export const configRepo = new HttpConfigRepository();
export const secretsRepo = new HttpSecretsRepository();
export const logsRepo = new HttpLogsRepository();
export const authRepo = new HttpAuthRepository();
