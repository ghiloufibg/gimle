import type { LucideIcon } from "lucide-react";

/** A sidebar link. Core screens declare theirs in `app-sidebar.tsx`; an addon screen declares its
 * own beside its route (see {@link collectAddonNavEntries}). */
export interface NavEntry {
  title: string;
  url: string;
  icon: LucideIcon;
  /** Which sidebar group the link renders under. An unknown group renders after every known one. */
  group: string;
  /** Match the current path exactly rather than as a prefix -- only "/" needs this. */
  exact?: boolean;
}

/** Sidebar groups, in render order. */
export const NAV_GROUPS = ["Cluster", "Workloads", "Edge", "Platform", "System"] as const;

/** Groups entries for rendering, dropping any group nothing landed in. */
export function groupNavEntries(entries: NavEntry[]): { group: string; items: NavEntry[] }[] {
  const known: string[] = [...NAV_GROUPS];
  const order = [...new Set([...known, ...entries.map((e) => e.group)])];
  return order
    .map((group) => ({ group, items: entries.filter((e) => e.group === group) }))
    .filter((g) => g.items.length > 0);
}
