/**
 * Composition root: the ONLY place that decides Mock vs Http.
 * Everything else imports from "@/repositories".
 */
import type { AuthRepository } from "./auth";
import { HttpAuthRepository } from "./http/auth";
import { HttpSecretsRepository } from "./http/secrets";
import { HttpStatusRepository } from "./http/status";
import type { SecretsRepository } from "./secrets";
import type { StatusRepository } from "./status";

export const authRepo: AuthRepository = new HttpAuthRepository();
export const statusRepo: StatusRepository = new HttpStatusRepository();
export const secretsRepo: SecretsRepository = new HttpSecretsRepository();

export type { AuthRepository, StatusRepository, SecretsRepository };
