import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpAuditRepository } from "./audit";
import { ApiError } from "./apiClient";
import { jsonResponse, stubFetchSequence, textResponse } from "./testUtil";
import type { AuditEvent } from "@/types";

afterEach(() => {
  vi.unstubAllGlobals();
});

const EVENT: AuditEvent = {
  id: "audit-1",
  principal: "alice",
  groups: ["gimle:operators"],
  resourceKind: "DEPLOYMENT",
  verb: "WRITE",
  tenantId: "acme",
  targetId: "orders-service",
  allowed: true,
  occurredAtEpochMilli: 1755000000000,
};

const STATUS = {
  retainedCount: 1,
  evictedTotal: 0,
  truncated: false,
  matchedCount: 1,
  cursorExpired: false,
};

describe("HttpAuditRepository.query", () => {
  it("requests plain /audit with no query string when every filter is empty", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ events: [EVENT], ...STATUS })]);
    const repo = new HttpAuditRepository();

    const result = await repo.query({});

    expect(result.events).toEqual([EVENT]);
    expect(result.truncated).toBe(false);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("/audit");
  });

  it("builds the query string from every set filter, independently combinable", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ events: [], ...STATUS })]);
    const repo = new HttpAuditRepository();

    await repo.query({
      principal: "alice",
      resource: "deployment",
      tenant: "acme",
      since: "2026-08-01T00:00:00Z",
      limit: 50,
    });

    // `since` goes on the wire as epoch millis, which is what the control plane parses -- the ISO
    // instant is only the screen's own datetime-input representation.
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe(
      "/audit?principal=alice&resource=deployment&tenant=acme&since=1785542400000&limit=50",
    );
  });

  it("omits an unparseable since rather than sending NaN as a filter", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ events: [], ...STATUS })]);
    const repo = new HttpAuditRepository();

    await repo.query({ since: "not a date" });

    expect(fetchMock.mock.calls[0][0]).toBe("/audit");
  });

  it("passes a page cursor through untouched alongside the filters it was issued under", async () => {
    const fetchMock = stubFetchSequence([() => jsonResponse({ events: [], ...STATUS })]);
    const repo = new HttpAuditRepository();

    await repo.query({ principal: "alice", limit: 2 }, "djE6YWxpY2U");

    expect(fetchMock.mock.calls[0][0]).toBe("/audit?principal=alice&limit=2&cursor=djE6YWxpY2U");
  });

  it("surfaces this query's own paging state alongside the events", async () => {
    stubFetchSequence([
      () =>
        jsonResponse({
          events: [EVENT],
          ...STATUS,
          matchedCount: 412,
          nextCursor: "djE6bmV4dA",
          cursorExpired: false,
        }),
    ]);
    const repo = new HttpAuditRepository();

    const result = await repo.query({ limit: 1 });

    expect(result.matchedCount).toBe(412);
    expect(result.nextCursor).toBe("djE6bmV4dA");
    expect(result.cursorExpired).toBe(false);
  });

  it("omits tenantId/targetId from the parsed result when the backend omitted them", async () => {
    const bare: AuditEvent = {
      id: "audit-2",
      principal: "svc-scheduler",
      groups: [],
      resourceKind: "NODE",
      verb: "READ",
      allowed: false,
      occurredAtEpochMilli: 1755000001000,
    };
    stubFetchSequence([() => jsonResponse({ events: [bare], ...STATUS })]);
    const repo = new HttpAuditRepository();

    const [event] = (await repo.query({})).events;

    expect(event.tenantId).toBeUndefined();
    expect(event.targetId).toBeUndefined();
    expect(event.allowed).toBe(false);
  });

  it("surfaces the trail's own retention state alongside the filtered events", async () => {
    stubFetchSequence([
      () =>
        jsonResponse({
          events: [EVENT],
          retainedCount: 50_000,
          evictedTotal: 137,
          oldestRetainedAtEpochMilli: 1755000000000,
          truncated: true,
        }),
    ]);
    const repo = new HttpAuditRepository();

    const result = await repo.query({});

    expect(result.truncated).toBe(true);
    expect(result.evictedTotal).toBe(137);
    expect(result.retainedCount).toBe(50_000);
    expect(result.oldestRetainedAtEpochMilli).toBe(1755000000000);
  });

  it("throws ApiError on a non-2xx response", async () => {
    stubFetchSequence([() => textResponse("forbidden", 403)]);
    const repo = new HttpAuditRepository();

    await expect(repo.query({})).rejects.toMatchObject(new ApiError(403, "forbidden"));
  });
});
