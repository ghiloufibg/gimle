import { describe, expect, it } from "vitest";
import { copiedMessage, copyActionLabel } from "./copy";

describe("copy control wording", () => {
  it("names an offered copy as the action it is", () => {
    expect(copyActionLabel("repository URL")).toBe("Copy repository URL");
  });

  it("reports a copy in the past tense only once it has happened", () => {
    expect(copiedMessage("repository URL")).toBe("repository URL copied");
  });
});
