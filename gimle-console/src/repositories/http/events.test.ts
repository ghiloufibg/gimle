import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpEventsRepository } from "./events";
import { jsonResponse, stubFetchSequence } from "./testUtil";

afterEach(() => {
  vi.unstubAllGlobals();
});

const ACTIVE_EVENT = {
  id: "evt-1",
  deploymentName: "greeter-consumer",
  instanceIndex: 0,
  kind: "ACTIVE",
  message: "instance active",
  occurredAtEpochMilli: 1_760_000_000_000,
};

describe("HttpEventsRepository", () => {
  it("GETs /events with the deployment and instance query parameters", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([ACTIVE_EVENT])]);
    const repo = new HttpEventsRepository();

    const events = await repo.fetchForInstance("greeter-consumer", 0);

    expect(events).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/events?deployment=greeter-consumer&instance=0");
    expect(init.method).toBe("GET");
  });

  it("appends ?tenant= only for a tenanted instance, url-encoding every value", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpEventsRepository();

    await repo.fetchForInstance("a b", 2, "acme corp");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/events?deployment=a+b&instance=2&tenant=acme+corp");
  });

  it("keeps an absent causeSummary absent rather than defaulting it", async () => {
    stubFetchSequence([() => jsonResponse([ACTIVE_EVENT])]);
    const repo = new HttpEventsRepository();

    const [event] = await repo.fetchForInstance("greeter-consumer", 0);

    expect(event.causeSummary).toBeUndefined();
  });

  it("surfaces the server's rejection of a non-numeric instance index", async () => {
    stubFetchSequence([() => new Response("instance must be an integer", { status: 400 })]);
    const repo = new HttpEventsRepository();

    await expect(repo.fetchForInstance("greeter-consumer", Number.NaN)).rejects.toThrow(
      "instance must be an integer",
    );
  });
});
