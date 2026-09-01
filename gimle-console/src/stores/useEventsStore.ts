import { create } from "zustand";
import type { InstanceEvent } from "@/types";
import { eventsRepo } from "@/repositories";
import { orderNewestFirst } from "@/lib/instance-events";

/**
 * One instance's lifecycle timeline at a time -- the instance detail page is the only screen that
 * reads it, and navigating between two instances must never leave the previous one's events on
 * screen. `key` records which `(tenant, deployment, index)` triple `items` actually belongs to, so
 * a slow response arriving after the operator has already navigated away is discarded rather than
 * rendered under the wrong instance's heading.
 */
export function instanceKey(
  deploymentName: string,
  instanceIndex: number,
  tenantId?: string | null,
): string {
  return `${tenantId ?? ""}/${deploymentName}#${instanceIndex}`;
}

interface State {
  key: string | null;
  items: InstanceEvent[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  load(deploymentName: string, instanceIndex: number, tenantId?: string | null): Promise<void>;
  reset(): void;
}

export const useEventsStore = create<State>((set, get) => ({
  key: null,
  items: [],
  loading: false,
  loaded: false,
  error: null,
  async load(deploymentName, instanceIndex, tenantId) {
    const key = instanceKey(deploymentName, instanceIndex, tenantId);
    if (get().loading && get().key === key) return;
    set({ key, items: [], loading: true, loaded: false, error: null });
    try {
      const events = await eventsRepo.fetchForInstance(deploymentName, instanceIndex, tenantId);
      if (get().key !== key) return;
      set({ items: orderNewestFirst(events), loading: false, loaded: true });
    } catch (e) {
      if (get().key !== key) return;
      set({ loading: false, error: (e as Error).message });
    }
  },
  reset() {
    set({ key: null, items: [], loading: false, loaded: false, error: null });
  },
}));
