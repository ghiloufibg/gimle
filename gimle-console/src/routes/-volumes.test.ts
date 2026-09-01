import { describe, expect, it } from "vitest";
import type { Volume } from "@/types";
import {
  describeVolume,
  emptyListingMessage,
  filterVolumes,
  isReclaimable,
  matchesFilter,
  reclaimableVolumes,
  totalUsedBytes,
  unreachableWarning,
  volumeKey,
  volumeState,
} from "./-volumes";

// Pure list/label/guard logic only -- this project's vitest config is deliberately
// node-environment (see vitest.config.ts); the JSX half of this screen is exercised live in a
// real browser instead, not here.

function volume(overrides: Partial<Volume> = {}): Volume {
  return {
    tenantId: "acme",
    statefulSet: "orders-store",
    instanceIndex: 0,
    volumeName: "data",
    usedBytes: 1024,
    path: "/var/lib/gimle/volumes/acme/orders-store/0/data",
    inUse: true,
    nodeId: "node-a",
    attached: true,
    ...overrides,
  };
}

describe("volumeKey", () => {
  it("separates two tenants' same-named volumes at the same index on one node", () => {
    const a = volume({ tenantId: "acme" });
    const b = volume({ tenantId: "globex" });
    expect(volumeKey(a)).not.toBe(volumeKey(b));
  });

  it("keeps an untenanted volume distinct from a tenanted one", () => {
    expect(volumeKey(volume({ tenantId: null }))).not.toBe(volumeKey(volume()));
  });

  it("separates two volume names on the same instance", () => {
    expect(volumeKey(volume({ volumeName: "data" }))).not.toBe(
      volumeKey(volume({ volumeName: "wal" })),
    );
  });
});

describe("volumeState", () => {
  it("reports a bound, held volume as in use", () => {
    expect(volumeState(volume({ attached: true, inUse: true })).label).toBe("in use");
  });

  it("reports a bound but unheld volume as attached, not orphaned", () => {
    const state = volumeState(volume({ attached: true, inUse: false }));
    expect(state.label).toBe("attached");
    expect(state.variant).toBe("info");
  });

  it("keeps a detached volume the agent still reports held out of the orphan bucket", () => {
    const state = volumeState(volume({ attached: false, inUse: true }));
    expect(state.label).toBe("in use, unbound");
    expect(state.variant).toBe("warn");
  });

  it("reports a volume nothing binds and nothing holds as orphaned", () => {
    expect(volumeState(volume({ attached: false, inUse: false })).label).toBe("orphaned");
  });
});

describe("isReclaimable", () => {
  it("admits only the volume nothing binds and nothing holds", () => {
    expect(isReclaimable(volume({ attached: false, inUse: false }))).toBe(true);
    expect(isReclaimable(volume({ attached: true, inUse: false }))).toBe(false);
    expect(isReclaimable(volume({ attached: false, inUse: true }))).toBe(false);
    expect(isReclaimable(volume({ attached: true, inUse: true }))).toBe(false);
  });
});

describe("describeVolume", () => {
  it("names the statefulset, index, volume and node the destroy would erase", () => {
    expect(describeVolume(volume({ instanceIndex: 3, nodeId: "node-b" }))).toBe(
      "orders-store[3] · data on node node-b (tenant acme)",
    );
  });

  it("says untenanted rather than dropping the tenant clause entirely", () => {
    expect(describeVolume(volume({ tenantId: null }))).toContain("(untenanted)");
  });
});

describe("matchesFilter", () => {
  const v = volume({ tenantId: "globex", statefulSet: "ledger", nodeId: "node-b" });

  it("matches nothing away on an empty or blank query", () => {
    expect(matchesFilter(v, "")).toBe(true);
    expect(matchesFilter(v, "   ")).toBe(true);
  });

  it("matches set, node, tenant, volume name, index and path, case-insensitively", () => {
    expect(matchesFilter(v, "LEDG")).toBe(true);
    expect(matchesFilter(v, "node-b")).toBe(true);
    expect(matchesFilter(v, "globex")).toBe(true);
    expect(matchesFilter(v, "data")).toBe(true);
    expect(matchesFilter(volume({ instanceIndex: 7 }), "7")).toBe(true);
    expect(matchesFilter(v, "/var/lib/gimle")).toBe(true);
    expect(matchesFilter(v, "nothing-like-this")).toBe(false);
  });

  it("never matches an untenanted volume by a tenant query", () => {
    const untenanted = volume({
      tenantId: null,
      path: "/var/lib/gimle/volumes/orders-store/0/data",
    });
    expect(matchesFilter(untenanted, "acme")).toBe(false);
  });
});

describe("filterVolumes / reclaimableVolumes / totalUsedBytes", () => {
  const volumes = [
    volume({ statefulSet: "orders-store", usedBytes: 100 }),
    volume({ statefulSet: "ledger", usedBytes: 200, attached: false, inUse: false }),
    volume({ statefulSet: "ledger", instanceIndex: 1, usedBytes: 300, attached: false }),
  ];

  it("filters by substring across the whole list", () => {
    expect(filterVolumes(volumes, "ledger")).toHaveLength(2);
  });

  it("keeps only genuinely orphaned volumes", () => {
    expect(reclaimableVolumes(volumes).map((v) => v.usedBytes)).toEqual([200]);
  });

  it("sums used bytes, and sums an empty list to zero", () => {
    expect(totalUsedBytes(volumes)).toBe(600);
    expect(totalUsedBytes([])).toBe(0);
  });
});

describe("empty and unreachable copy", () => {
  it("calls an empty complete listing none", () => {
    expect(emptyListingMessage([])).toBe("No volumes on any node.");
  });

  it("calls an empty listing with a dark node unknown rather than none", () => {
    const message = emptyListingMessage(["node-c"]);
    expect(message).toContain("1 node(s) could not be reached");
    expect(message).not.toBe("No volumes on any node.");
  });

  it("names every unreachable node in the warning strip", () => {
    const warning = unreachableWarning(["node-c", "node-d"]);
    expect(warning).toContain("node-c, node-d");
    expect(warning).toContain("incomplete, not empty");
  });
});
