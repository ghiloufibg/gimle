import type { AuthzVocabulary, AuthzVocabularyRepository } from "@/repositories/authzVocabulary";
import { requestJson } from "./apiClient";

/**
 * `GET /authz/vocabulary` -- the control plane's own `ResourceKind`/`Verb` enums, so the Roles
 * permission editor offers exactly what the `Authorizer` on the other end will accept rather than a
 * hand-maintained copy that falls behind every time the platform grows a kind.
 */
export class HttpAuthzVocabularyRepository implements AuthzVocabularyRepository {
  async fetch(): Promise<AuthzVocabulary> {
    const raw = await requestJson<{ resourceKinds?: string[]; verbs?: string[] }>(
      "GET",
      "/authz/vocabulary",
    );
    return { resourceKinds: raw.resourceKinds ?? [], verbs: raw.verbs ?? [] };
  }
}
