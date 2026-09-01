import { create } from "zustand";
import type { SecretMetadata, SecretType, SecretValue, SecretVersion } from "@/types";
import { secretsRepo } from "@/repositories";

const PAGE = 50;

interface State {
  tenantId: string | null;
  items: SecretMetadata[];
  nextCursor: string | null;
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  // Fetched on demand, not present in the list response -- keyed by secret key.
  revealed: Record<string, SecretValue>;
  versions: Record<string, SecretVersion[]>;
  setTenant(id: string | null): void;
  loadFirstPage(): Promise<void>;
  loadMore(): Promise<void>;
  refresh(): Promise<void>;
  reveal(key: string, version?: number): Promise<void>;
  hide(key: string): void;
  loadVersions(key: string): Promise<void>;
  upsert(key: string, value: string, type?: SecretType): Promise<void>;
  remove(key: string, destroy: boolean): Promise<void>;
  rotateKey(): Promise<number>;
}

export const useSecretsStore = create<State>((set, get) => ({
  tenantId: null,
  items: [],
  nextCursor: null,
  hasMore: true,
  loading: false,
  error: null,
  revealed: {},
  versions: {},
  setTenant(id) {
    set({
      tenantId: id,
      items: [],
      nextCursor: null,
      hasMore: true,
      error: null,
      revealed: {},
      versions: {},
    });
  },
  async loadFirstPage() {
    const { tenantId } = get();
    if (!tenantId || get().loading) return;
    set({ loading: true, error: null });
    try {
      const p = await secretsRepo.fetchPage({ tenantId, cursor: null, pageSize: PAGE });
      set({
        items: p.items,
        nextCursor: p.nextCursor,
        hasMore: p.nextCursor !== null,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async loadMore() {
    const { tenantId, loading, hasMore, nextCursor, items } = get();
    if (!tenantId || loading || !hasMore) return;
    set({ loading: true });
    try {
      const p = await secretsRepo.fetchPage({ tenantId, cursor: nextCursor, pageSize: PAGE });
      set({
        items: [...items, ...p.items],
        nextCursor: p.nextCursor,
        hasMore: p.nextCursor !== null,
        loading: false,
      });
    } catch (e) {
      set({ loading: false, error: (e as Error).message });
    }
  },
  async refresh() {
    set({ items: [], nextCursor: null, hasMore: true, revealed: {}, versions: {} });
    await get().loadFirstPage();
  },
  async reveal(key, version) {
    const { tenantId } = get();
    if (!tenantId) return;
    try {
      const value = await secretsRepo.fetchValue(tenantId, key, version);
      set({ revealed: { ...get().revealed, [key]: value } });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  hide(key) {
    const revealed = { ...get().revealed };
    delete revealed[key];
    set({ revealed });
  },
  async loadVersions(key) {
    const { tenantId } = get();
    if (!tenantId) return;
    try {
      const list = await secretsRepo.fetchVersions(tenantId, key);
      set({ versions: { ...get().versions, [key]: list } });
    } catch (e) {
      set({ error: (e as Error).message });
    }
  },
  async upsert(key, value, type) {
    const { tenantId } = get();
    if (!tenantId) return;
    const saved = await secretsRepo.upsert(tenantId, key, value, type);
    const list = get().items;
    const i = list.findIndex((s) => s.key === key);
    set({ items: i >= 0 ? list.map((s, ix) => (ix === i ? saved : s)) : [...list, saved] });
    // The value/version list just changed underneath any stale reveal/versions cache for this key.
    forgetCachedValueAndVersions(get, set, key);
  },
  async remove(key, destroy) {
    const { tenantId, items } = get();
    if (!tenantId) return;
    await secretsRepo.remove(tenantId, key, destroy);
    forgetCachedValueAndVersions(get, set, key);
    set({
      items: destroy
        ? items.filter((s) => s.key !== key)
        : items.map((s) => (s.key === key ? { ...s, deleted: true } : s)),
    });
  },
  async rotateKey() {
    return secretsRepo.rotateKey();
  },
}));

function forgetCachedValueAndVersions(
  get: () => State,
  set: (partial: Partial<State>) => void,
  key: string,
): void {
  const revealed = { ...get().revealed };
  delete revealed[key];
  const versions = { ...get().versions };
  delete versions[key];
  set({ revealed, versions });
}
