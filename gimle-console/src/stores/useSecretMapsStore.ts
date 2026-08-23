import { create } from "zustand";
import type { SecretMap, SecretMapGroupVersion, SecretMapKeyResult } from "@/types";
import { secretMapsRepo } from "@/repositories";

interface State {
  tenantId: string | null;
  names: string[];
  loading: boolean;
  error: string | null;
  // The SecretMap currently open in the edit panel, if any -- null selects "create new".
  selected: SecretMap | null;
  // Per-key outcome of the most recent save() -- unlike ConfigMap there is no single object-level
  // version to conflict-check against, so this is informational (which keys landed, which
  // failed), never a blocking prompt the way ConfigMap's `conflict` is.
  lastSetResults: SecretMapKeyResult[] | null;
  // The selected SecretMap's own group-version history, oldest first -- loaded alongside it in
  // select(), refreshed after every save()/rollback() the same way `selected` itself is.
  groupVersions: SecretMapGroupVersion[];
  setTenant(id: string | null): void;
  loadNames(): Promise<void>;
  select(name: string | null): Promise<void>;
  save(name: string, data: Record<string, string>): Promise<void>;
  remove(name: string, destroy?: boolean): Promise<void>;
  rollback(name: string, groupVersion: number): Promise<void>;
}

export const useSecretMapsStore = create<State>((set, get) => ({
  tenantId: null,
  names: [],
  loading: false,
  error: null,
  selected: null,
  lastSetResults: null,
  groupVersions: [],
  setTenant(id) {
    set({
      tenantId: id,
      names: [],
      error: null,
      selected: null,
      lastSetResults: null,
      groupVersions: [],
    });
  },
  async loadNames() {
    const { tenantId } = get();
    if (!tenantId) return;
    set({ loading: true, error: null });
    try {
      const names = await secretMapsRepo.fetchNames(tenantId);
      set({ names, loading: false });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async select(name) {
    const { tenantId } = get();
    set({ lastSetResults: null });
    if (!tenantId || name === null) {
      set({ selected: null, groupVersions: [] });
      return;
    }
    try {
      const [secretMap, groupVersions] = await Promise.all([
        secretMapsRepo.fetchOne(tenantId, name),
        secretMapsRepo.fetchGroupVersions(tenantId, name),
      ]);
      set({ selected: secretMap, groupVersions });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async save(name, data) {
    const { tenantId, names } = get();
    if (!tenantId) return;
    try {
      const results = await secretMapsRepo.set(tenantId, name, data);
      const [refreshed, groupVersions] = await Promise.all([
        secretMapsRepo.fetchOne(tenantId, name),
        secretMapsRepo.fetchGroupVersions(tenantId, name),
      ]);
      set({
        selected: refreshed,
        names: names.includes(name) ? names : [...names, name],
        lastSetResults: results,
        groupVersions,
      });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async remove(name, destroy) {
    const { tenantId, names, selected } = get();
    if (!tenantId) return;
    await secretMapsRepo.remove(tenantId, name, destroy);
    set({
      names: names.filter((n) => n !== name),
      selected: selected?.name === name ? null : selected,
      groupVersions: selected?.name === name ? [] : get().groupVersions,
    });
  },
  async rollback(name, groupVersion) {
    const { tenantId } = get();
    if (!tenantId) return;
    try {
      const result = await secretMapsRepo.rollback(tenantId, name, groupVersion);
      const [refreshed, groupVersions] = await Promise.all([
        secretMapsRepo.fetchOne(tenantId, name),
        secretMapsRepo.fetchGroupVersions(tenantId, name),
      ]);
      set({ selected: refreshed, groupVersions, lastSetResults: result.results });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
}));
