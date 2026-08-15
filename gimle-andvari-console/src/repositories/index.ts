/**
 * Composition root: the ONLY place that decides Mock vs Http.
 * Everything else imports from "@/repositories".
 *
 * Always wired to the real Http* implementations -- matching gimle-console's and
 * gimle-fafnir-console's own repositories/index.ts exactly, deliberately with no
 * env-var/build-mode toggle back to Mock*: this module ships inside gimle-andvari's own jar and
 * is served to a real operator against a real registry, so there is no safe default other than
 * "always talk to the real backend." The Mock* implementations stay in mock/mockRepositories.ts
 * for Vitest coverage (see repositories/__tests__/repositories.test.ts) and as a reference
 * implementation of the same interfaces, not as a runtime-selectable mode.
 */
import type { ArtifactsRepository, AuthRepository, StatusRepository } from "./types";
import { HttpArtifactsRepository } from "./http/artifactsRepository";
import { HttpAuthRepository } from "./http/authRepository";
import { HttpStatusRepository } from "./http/statusRepository";

export const authRepo: AuthRepository = new HttpAuthRepository();
export const statusRepo: StatusRepository = new HttpStatusRepository();
export const artifactsRepo: ArtifactsRepository = new HttpArtifactsRepository();

export { ApiError } from "./http/apiClient";
export type { ArtifactsRepository, AuthRepository, StatusRepository } from "./types";
