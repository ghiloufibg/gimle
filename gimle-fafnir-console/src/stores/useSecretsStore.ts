import { create } from "zustand";

import { messageOf } from "@/lib/errors";
import { secretsRepo } from "@/repositories";
import type { SecretMetadata } from "@/types";

export interface RevealedSecret {
  value: string;
  version: number;
  versions: number[];
  loading: boolean;
}

interface SecretsState {
  tenantId: string;
  secrets: SecretMetadata[];
  loading: boolean;
  error: string | null;
  revealed: Record<string, RevealedSecret>;
  writing: boolean;
  rotating: boolean;
  setTenant: (tenantId: string) => void;
  loadSecrets: (tenantId: string) => Promise<void>;
  reveal: (key: string) => Promise<void>;
  hide: (key: string) => void;
  selectVersion: (key: string, version: number) => Promise<void>;
  save: (key: string, value: string) => Promise<number | null>;
  remove: (key: string, destroy: boolean) => Promise<boolean>;
  rotateKey: () => Promise<number | null>;
}

export const useSecretsStore = create<SecretsState>((set, get) => ({
  tenantId: "",
  secrets: [],
  loading: false,
  error: null,
  revealed: {},
  writing: false,
  rotating: false,

  setTenant(tenantId) {
    set({ tenantId, revealed: {} });
  },

  async loadSecrets(tenantId) {
    if (!tenantId) {
      set({ secrets: [], error: null, loading: false, revealed: {} });
      return;
    }
    set({ loading: true, error: null, tenantId, revealed: {} });
    try {
      const secrets = await secretsRepo.list(tenantId);
      set({ secrets, loading: false });
    } catch (error) {
      set({ secrets: [], loading: false, error: messageOf(error) });
    }
  },

  async reveal(key) {
    const { tenantId, revealed } = get();
    set({
      revealed: {
        ...revealed,
        [key]: { value: "", version: 0, versions: [], loading: true },
      },
    });
    try {
      const [secret, versions] = await Promise.all([
        secretsRepo.read(tenantId, key),
        secretsRepo.versions(tenantId, key),
      ]);
      set((state) => ({
        revealed: {
          ...state.revealed,
          [key]: { value: secret.value, version: secret.version, versions, loading: false },
        },
      }));
    } catch (error) {
      set((state) => {
        const next = { ...state.revealed };
        delete next[key];
        return { revealed: next, error: messageOf(error) };
      });
    }
  },

  hide(key) {
    set((state) => {
      const next = { ...state.revealed };
      delete next[key];
      return { revealed: next };
    });
  },

  async selectVersion(key, version) {
    const { tenantId } = get();
    set((state) => {
      const current = state.revealed[key];
      if (!current) return {} as Partial<SecretsState>;
      return { revealed: { ...state.revealed, [key]: { ...current, loading: true } } };
    });
    try {
      const secret = await secretsRepo.read(tenantId, key, version);
      set((state) => {
        const current = state.revealed[key];
        if (!current) return {} as Partial<SecretsState>;
        return {
          revealed: {
            ...state.revealed,
            [key]: { ...current, value: secret.value, version: secret.version, loading: false },
          },
        };
      });
    } catch (error) {
      set((state) => {
        const current = state.revealed[key];
        if (!current) return { error: messageOf(error) } as Partial<SecretsState>;
        return {
          revealed: { ...state.revealed, [key]: { ...current, loading: false } },
          error: messageOf(error),
        };
      });
    }
  },

  async save(key, value) {
    const { tenantId } = get();
    set({ writing: true });
    try {
      const version = await secretsRepo.upsert(tenantId, key, value);
      set({ writing: false });
      await get().loadSecrets(tenantId);
      return version;
    } catch (error) {
      set({ writing: false, error: messageOf(error) });
      return null;
    }
  },

  async remove(key, destroy) {
    const { tenantId } = get();
    try {
      await secretsRepo.remove(tenantId, key, destroy);
      await get().loadSecrets(tenantId);
      return true;
    } catch (error) {
      set({ error: messageOf(error) });
      return false;
    }
  },

  async rotateKey() {
    set({ rotating: true });
    try {
      const activeKeyId = await secretsRepo.rotateKey();
      set({ rotating: false });
      return activeKeyId;
    } catch (error) {
      set({ rotating: false, error: messageOf(error) });
      return null;
    }
  },
}));
