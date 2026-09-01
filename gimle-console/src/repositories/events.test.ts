import { describe, expect, it } from "vitest";
import { MockEventsRepository } from "./events";

describe("MockEventsRepository", () => {
  const repo = new MockEventsRepository();

  it("returns an instance's timeline newest-first", async () => {
    const events = await repo.fetchForInstance("greeter-consumer", 0);
    expect(events.map((e) => e.kind)).toEqual([
      "ACTIVE",
      "STARTING",
      "TRANSITION_FAILED",
      "INSTALLED",
    ]);
  });

  it("carries a causeSummary only on a failed transition", async () => {
    const events = await repo.fetchForInstance("greeter-consumer", 0);
    const failed = events.find((e) => e.kind === "TRANSITION_FAILED");
    expect(failed?.causeSummary).toContain("GimleResolutionException");
    expect(events.find((e) => e.kind === "ACTIVE")?.causeSummary).toBeUndefined();
  });

  it("returns an empty timeline for an instance with no events", async () => {
    expect(await repo.fetchForInstance("greeter-consumer", 7)).toEqual([]);
    expect(await repo.fetchForInstance("nope", 0)).toEqual([]);
  });
});
