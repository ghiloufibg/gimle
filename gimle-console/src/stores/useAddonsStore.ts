import { create } from "zustand";

import { ADDONS, type Addon } from "@/addons";

/**
 * Which bundled addons the control plane serving this console actually advertises.
 *
 * An addon always ships inside the bundle, so "disabled" cannot mean "not present" -- it means the
 * server does not advertise it: no sidebar entry, and its route explains itself instead of
 * rendering. The answer comes from `GET /console/addons`, read once at startup alongside the auth
 * session, because the property behind it is read once at the control plane's own startup too.
 */
interface AddonsState {
  /** Advertised ids. Empty until `init` has answered -- read `initialized` to tell those apart. */
  enabledIds: string[];
  initialized: boolean;
  init(): Promise<void>;
  isEnabled(id: string): boolean;
  enabledAddons(): Addon[];
}

interface AddonsResponse {
  addons: { id: string; enabled: boolean }[];
}

export const useAddonsStore = create<AddonsState>((set, get) => ({
  enabledIds: [],
  initialized: false,
  async init() {
    if (get().initialized) return;
    try {
      const res = await fetch("/console/addons");
      if (!res.ok) throw new Error(`control plane answered ${res.status}`);
      const body = (await res.json()) as AddonsResponse;
      set({
        enabledIds: body.addons.filter((a) => a.enabled).map((a) => a.id),
        initialized: true,
      });
    } catch {
      // A control plane too old to serve this route, or unreachable, advertises nothing rather
      // than failing the console: the same posture useAuthStore takes on a failed session read.
      // Every addon route still explains itself, so a shared link is never a bare 404.
      set({ enabledIds: [], initialized: true });
    }
  },
  isEnabled(id) {
    return get().enabledIds.includes(id);
  },
  enabledAddons() {
    const enabled = get().enabledIds;
    return ADDONS.filter((a) => enabled.includes(a.id));
  },
}));
