import { describe, expect, it } from "vitest";
import { MockAuthzVocabularyRepository } from "./authzVocabulary";
import { RESOURCE_KINDS, VERBS } from "@/types";

describe("MockAuthzVocabularyRepository", () => {
  const repo = new MockAuthzVocabularyRepository();

  it("serves the bundled vocabulary, matching what the real endpoint answers with", async () => {
    const vocabulary = await repo.fetch();
    expect(vocabulary.resourceKinds).toEqual([...RESOURCE_KINDS]);
    expect(vocabulary.verbs).toEqual([...VERBS]);
  });

  it("hands out a copy, so a consumer cannot mutate the shared constant", async () => {
    (await repo.fetch()).resourceKinds.push("MUTATED");
    expect(RESOURCE_KINDS).not.toContain("MUTATED");
  });
});
