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

  it("never sends a limit parameter the route would silently ignore", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpEventsRepository();

    await repo.fetchForInstance("greeter-consumer", 0, "acme");

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).not.toContain("limit");
  });

  it("omits ?tenant= for an explicitly untenanted instance rather than sending an empty one", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpEventsRepository();

    await repo.fetchForInstance("greeter-consumer", 0, null);

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/events?deployment=greeter-consumer&instance=0");
  });

  it("returns an empty timeline as an empty array rather than as a failure", async () => {
    stubFetchSequence([() => jsonResponse([])]);
    const repo = new HttpEventsRepository();

    expect(await repo.fetchForInstance("greeter-consumer", 3)).toEqual([]);
  });

  it("carries a failed transition's causeSummary through unchanged", async () => {
    stubFetchSequence([
      () =>
        jsonResponse([
          {
            ...ACTIVE_EVENT,
            kind: "TRANSITION_FAILED",
            message: "could not resolve module artifact",
            causeSummary: "GimleResolutionException: no artifact for greeter-consumer@1.0.0",
          },
        ]),
    ]);
    const repo = new HttpEventsRepository();

    const [event] = await repo.fetchForInstance("greeter-consumer", 0);

    expect(event.kind).toBe("TRANSITION_FAILED");
    expect(event.causeSummary).toBe(
      "GimleResolutionException: no artifact for greeter-consumer@1.0.0",
    );
  });

  it("surfaces an authorization failure rather than rendering an empty timeline", async () => {
    stubFetchSequence([() => new Response("forbidden", { status: 403 })]);
    const repo = new HttpEventsRepository();

    await expect(repo.fetchForInstance("greeter-consumer", 0)).rejects.toThrow();
  });

  it("surfaces the server's rejection of a non-numeric instance index", async () => {
    stubFetchSequence([() => new Response("instance must be an integer", { status: 400 })]);
    const repo = new HttpEventsRepository();

    await expect(repo.fetchForInstance("greeter-consumer", Number.NaN)).rejects.toThrow(
      "instance must be an integer",
    );
  });
});
