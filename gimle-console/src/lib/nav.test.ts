import { describe, expect, it } from "vitest";
import type { LucideIcon } from "lucide-react";
import { groupNavEntries, NAV_GROUPS, type NavEntry } from "./nav";

// Which group an addon lands in is its own catalog entry's business (see addons.json and
// useAddonsStore.test.ts); this file covers only how groups are ordered and folded once built.

const icon = (() => null) as unknown as LucideIcon;

function entry(title: string, group: string): NavEntry {
  return { title, url: `/${title.toLowerCase()}`, icon, group };
}

describe("groupNavEntries", () => {
  it("renders known groups in declared order regardless of entry order", () => {
    const grouped = groupNavEntries([
      entry("Audit", "Platform"),
      entry("Gateway", "Edge"),
      entry("Overview", "Cluster"),
    ]);
    expect(grouped.map((g) => g.group)).toEqual(["Cluster", "Edge", "Platform"]);
  });

  it("keeps each group's entries in the order they were declared", () => {
    const grouped = groupNavEntries([
      entry("Networking", "Edge"),
      entry("Gateway", "Edge"),
      entry("Skald", "Edge"),
    ]);
    expect(grouped[0].items.map((i) => i.title)).toEqual(["Networking", "Gateway", "Skald"]);
  });

  it("drops groups nothing landed in", () => {
    const grouped = groupNavEntries([entry("Overview", "Cluster")]);
    expect(grouped).toHaveLength(1);
    expect(NAV_GROUPS.length).toBeGreaterThan(1);
  });

  it("renders an unknown group once, after every known one", () => {
    const grouped = groupNavEntries([
      entry("Something", "Experimental"),
      entry("Else", "Experimental"),
      entry("Overview", "Cluster"),
    ]);
    expect(grouped.map((g) => g.group)).toEqual(["Cluster", "Experimental"]);
    expect(grouped[1].items).toHaveLength(2);
  });
});
