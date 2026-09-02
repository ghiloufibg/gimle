import { Globe, Layers, Waypoints, type LucideIcon } from "lucide-react";

import catalog from "../../public/addons.json";

/**
 * One console addon: a screen that always ships inside this bundle but is only reachable when the
 * control plane serving it advertises the addon's id (see `useAddonsStore` and
 * `-Dgimle.controlplane.consoleAddons`).
 */
export interface Addon {
  id: string;
  title: string;
  description: string;
  /** The route the screen mounts at, matching its file under `src/routes/`. */
  route: string;
  /**
   * The sidebar group this addon renders under when advertised. Carried in the catalog rather than
   * fixed in the sidebar, so an addon sits beside the screens it belongs with -- Gateway and Skald
   * DNS under `Edge` next to Networking -- and one that belongs nowhere existing can name its own
   * group without a code change.
   */
  group: string;
  icon: LucideIcon;
}

/**
 * The bundled catalog is `public/addons.json`, not a list maintained here: Vite copies `public/`
 * into `dist/` verbatim, so the same file lands in the jar at `console/addons.json`, where
 * `ControlPlaneMain` reads it to validate the property. This file adds only what JSON cannot carry
 * -- the icon component -- so neither side can drift from a list the other owns.
 */
const ICONS: Record<string, LucideIcon> = {
  applications: Layers,
  gateway: Waypoints,
  skald: Globe,
};

export const ADDONS: Addon[] = catalog.addons.map((entry) => {
  const icon = ICONS[entry.id];
  if (icon === undefined) {
    // A catalog entry with no icon here is a half-finished addon, not something to render blank.
    throw new Error(
      `addons.json declares '${entry.id}' but src/addons/index.ts has no icon for it`,
    );
  }
  return { ...entry, icon };
});

/** The addon a route file belongs to. Throws rather than returning undefined: a route naming an id
 * the catalog does not carry is a build-time mistake, not a runtime state to render. */
export function addonById(id: string): Addon {
  const addon = ADDONS.find((a) => a.id === id);
  if (addon === undefined) {
    throw new Error(
      `no addon '${id}' in addons.json (have: ${ADDONS.map((a) => a.id).join(", ")})`,
    );
  }
  return addon;
}
