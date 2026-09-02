import type { NavEntry } from "./nav";

/**
 * Every `navEntry` exported by a route file under `src/routes/`.
 *
 * An addon screen is one route file plus its wiring, and it has to be removable by deleting that
 * file: TanStack Router regenerates its route tree from whatever sits in `src/routes/`, so a
 * hand-maintained sidebar array would be the one place still naming a route that no longer exists.
 * Globbing for the descriptor instead keeps the two in step by construction -- the file declares
 * its own link, and deleting the file takes the link with it.
 *
 * Eager, because the generated route tree already imports every one of these modules statically:
 * there is no code-splitting here to lose. `__root.tsx` is excluded because it renders the sidebar
 * itself, and a glob that pulled it back in would make this module's own importer circular.
 */
export function collectAddonNavEntries(): NavEntry[] {
  const modules = import.meta.glob<{ navEntry?: NavEntry }>(
    ["../routes/*.tsx", "!../routes/__root.tsx"],
    { eager: true },
  );
  const entries: NavEntry[] = [];
  for (const path of Object.keys(modules).sort()) {
    const entry = modules[path].navEntry;
    if (entry) entries.push(entry);
  }
  return entries;
}
