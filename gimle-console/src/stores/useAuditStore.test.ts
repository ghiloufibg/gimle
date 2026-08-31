import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  auditRepo: { query: vi.fn() },
}));

import { auditRepo } from "@/repositories";
import { useAuditStore } from "./useAuditStore";
import type { AuditEvent } from "@/types";

const OLDER: AuditEvent = {
  id: "audit-1",
  principal: "alice",
  groups: [],
  resourceKind: "DEPLOYMENT",
  verb: "WRITE",
  allowed: true,
  occurredAtEpochMilli: 1000,
};
const NEWER: AuditEvent = {
  id: "audit-2",
  principal: "bjorn",
  groups: [],
  resourceKind: "NODE",
  verb: "READ",
  allowed: false,
  occurredAtEpochMilli: 2000,
};

describe("useAuditStore", () => {
  beforeEach(() => {
    useAuditStore.setState({
      items: [],
      status: null,
      filter: { limit: 100 },
      loading: false,
      loaded: false,
      error: null,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("search surfaces a repository rejection as store.error and clears loading", async () => {
    vi.mocked(auditRepo.query).mockRejectedValueOnce(new Error("control plane unreachable"));

    await useAuditStore.getState().search();

    const state = useAuditStore.getState();
    expect(state.error).toBe("control plane unreachable");
    expect(state.loading).toBe(false);
    expect(state.loaded).toBe(false);
  });

  it("a successful search sorts results newest-first regardless of response order", async () => {
    vi.mocked(auditRepo.query).mockResolvedValueOnce({
      events: [OLDER, NEWER],
      retainedCount: 2,
      evictedTotal: 0,
      truncated: false,
    });

    await useAuditStore.getState().search();

    const state = useAuditStore.getState();
    expect(state.items.map((e) => e.id)).toEqual(["audit-2", "audit-1"]);
    expect(state.loaded).toBe(true);
    expect(state.error).toBeNull();
  });

  it("a successful search surfaces the trail's own retention status", async () => {
    vi.mocked(auditRepo.query).mockResolvedValueOnce({
      events: [],
      retainedCount: 50_000,
      evictedTotal: 12,
      truncated: true,
    });

    await useAuditStore.getState().search();

    expect(useAuditStore.getState().status).toEqual({
      retainedCount: 50_000,
      evictedTotal: 12,
      truncated: true,
    });
  });

  it("setFilter drops empty-string and undefined values instead of sending them as filters", () => {
    useAuditStore.getState().setFilter({ principal: "alice" });
    useAuditStore.getState().setFilter({ principal: "" });

    expect(useAuditStore.getState().filter.principal).toBeUndefined();
  });

  it("setFilter re-defaults limit if cleared to a falsy value", () => {
    useAuditStore.getState().setFilter({ limit: 0 });

    expect(useAuditStore.getState().filter.limit).toBe(100);
  });

  it("reset clears the filter back to defaults and re-runs search", async () => {
    useAuditStore.getState().setFilter({ principal: "alice", limit: 5 });
    vi.mocked(auditRepo.query).mockResolvedValueOnce({
      events: [],
      retainedCount: 0,
      evictedTotal: 0,
      truncated: false,
    });

    await useAuditStore.getState().reset();

    expect(useAuditStore.getState().filter).toEqual({ limit: 100 });
    expect(auditRepo.query).toHaveBeenCalledWith({ limit: 100 });
  });
});
