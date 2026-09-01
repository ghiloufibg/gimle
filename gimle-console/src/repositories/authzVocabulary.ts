import { RESOURCE_KINDS, VERBS } from "@/types";
import { delay } from "./util";

/** The permission vocabulary the running control plane actually enforces. */
export interface AuthzVocabulary {
  resourceKinds: string[];
  verbs: string[];
}

/**
 * Deliberately typed as plain strings rather than the `ResourceKind`/`Verb` unions: the whole point
 * of reading this from the server is that it may name a kind this build's own union predates.
 */
export interface AuthzVocabularyRepository {
  fetch(): Promise<AuthzVocabulary>;
}

export class MockAuthzVocabularyRepository implements AuthzVocabularyRepository {
  async fetch(): Promise<AuthzVocabulary> {
    return delay({ resourceKinds: [...RESOURCE_KINDS], verbs: [...VERBS] });
  }
}
