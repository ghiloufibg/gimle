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

const STATUS = { retainedCount: 1, evictedTotal: 0, truncated: false };

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

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe(
      "/audit?principal=alice&resource=deployment&tenant=acme&since=2026-08-01T00%3A00%3A00Z&limit=50",
    );
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
