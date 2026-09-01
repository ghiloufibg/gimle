import { describe, expect, it } from "vitest";
import {
  boundTimeline,
  eventKindTone,
  failureCount,
  INSTANCE_EVENT_PAGE,
  orderNewestFirst,
} from "@/lib/instance-events";
import type { InstanceEvent, InstanceEventKind } from "@/types";

// Pure ordering/bounding/tone logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the panel that renders these results is exercised live
// in a real browser instead, not here.

function event(overrides: Partial<InstanceEvent> = {}): InstanceEvent {
  return {
    id: "evt-1",
    deploymentName: "greeter-consumer",
    instanceIndex: 0,
    kind: "ACTIVE",
    message: "instance active",
    occurredAtEpochMilli: 1_000,
    ...overrides,
  };
}

function timeline(size: number): InstanceEvent[] {
  return Array.from({ length: size }, (_, i) =>
    event({ id: `evt-${i}`, occurredAtEpochMilli: 10_000 - i }),
  );
}

describe("eventKindTone", () => {
  it("tints only a failed transition as a failure", () => {
    expect(eventKindTone("TRANSITION_FAILED")).toBe("bad");
    const routine: InstanceEventKind[] = [
      "INSTALLED",
      "RESOLVED",
      "STARTING",
      "ACTIVE",
      "STOPPING",
      "UNINSTALLED",
      "COMPLETED",
    ];
    for (const kind of routine) {
      expect(eventKindTone(kind)).not.toBe("bad");
    }
  });

  it("separates settled, in-flight and preparatory transitions from one another", () => {
    expect(eventKindTone("ACTIVE")).toBe("ok");
    expect(eventKindTone("COMPLETED")).toBe("ok");
    expect(eventKindTone("STARTING")).toBe("warn");
    expect(eventKindTone("STOPPING")).toBe("warn");
    expect(eventKindTone("INSTALLED")).toBe("info");
    expect(eventKindTone("RESOLVED")).toBe("info");
  });

  it("treats a deliberate teardown as routine rather than as a failure", () => {
    expect(eventKindTone("UNINSTALLED")).toBe("muted");
  });
});

describe("orderNewestFirst", () => {
  it("puts the newest transition first regardless of response order", () => {
    const ordered = orderNewestFirst([
      event({ id: "old", occurredAtEpochMilli: 1 }),
      event({ id: "new", occurredAtEpochMilli: 3 }),
      event({ id: "mid", occurredAtEpochMilli: 2 }),
    ]);
    expect(ordered.map((e) => e.id)).toEqual(["new", "mid", "old"]);
  });

  it("keeps the server's own relative order for transitions sharing a millisecond", () => {
    const ordered = orderNewestFirst([
      event({ id: "a", occurredAtEpochMilli: 5 }),
      event({ id: "b", occurredAtEpochMilli: 5 }),
      event({ id: "c", occurredAtEpochMilli: 5 }),
    ]);
    expect(ordered.map((e) => e.id)).toEqual(["a", "b", "c"]);
  });

  it("does not mutate the array it was given", () => {
    const input = [
      event({ id: "old", occurredAtEpochMilli: 1 }),
      event({ id: "new", occurredAtEpochMilli: 3 }),
    ];
    orderNewestFirst(input);
    expect(input.map((e) => e.id)).toEqual(["old", "new"]);
  });

  it("handles an empty timeline", () => {
    expect(orderNewestFirst([])).toEqual([]);
  });
});

describe("boundTimeline", () => {
  it("shows everything and hides nothing when the timeline fits the page", () => {
    const events = timeline(INSTANCE_EVENT_PAGE);
    expect(boundTimeline(events, false)).toEqual({ visible: events, hiddenCount: 0 });
  });

  it("truncates a longer timeline to the page, reporting what it held back", () => {
    const bounded = boundTimeline(timeline(INSTANCE_EVENT_PAGE + 7), false);
    expect(bounded.visible).toHaveLength(INSTANCE_EVENT_PAGE);
    expect(bounded.hiddenCount).toBe(7);
  });

  it("keeps the newest entries, not the oldest, when it truncates", () => {
    const bounded = boundTimeline(timeline(INSTANCE_EVENT_PAGE + 3), false, INSTANCE_EVENT_PAGE);
    expect(bounded.visible[0].id).toBe("evt-0");
    expect(bounded.visible.at(-1)?.id).toBe(`evt-${INSTANCE_EVENT_PAGE - 1}`);
  });

  it("shows the whole timeline once expanded", () => {
    const events = timeline(40);
    const bounded = boundTimeline(events, true);
    expect(bounded.visible).toHaveLength(40);
    expect(bounded.hiddenCount).toBe(0);
  });

  it("honours an explicit page size", () => {
    const bounded = boundTimeline(timeline(5), false, 2);
    expect(bounded.visible).toHaveLength(2);
    expect(bounded.hiddenCount).toBe(3);
  });
});

describe("failureCount", () => {
  it("counts only failed transitions across the whole timeline", () => {
    expect(
      failureCount([
        event({ kind: "TRANSITION_FAILED" }),
        event({ kind: "STARTING" }),
        event({ kind: "TRANSITION_FAILED" }),
        event({ kind: "ACTIVE" }),
      ]),
    ).toBe(2);
  });

  it("is zero for a timeline that never failed", () => {
    expect(failureCount([event({ kind: "ACTIVE" })])).toBe(0);
    expect(failureCount([])).toBe(0);
  });
});
