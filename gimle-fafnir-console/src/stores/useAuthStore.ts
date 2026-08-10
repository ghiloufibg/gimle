import { create } from "zustand";

import { messageOf } from "@/lib/errors";
import { authRepo } from "@/repositories";
import { setUnauthorizedHandler } from "@/repositories/http/apiClient";
import type { Principal } from "@/types";

interface AuthState {
  principal: Principal | null;
  bootstrapped: boolean;
  pending: boolean;
  error: string | null;
  checkSession: () => Promise<void>;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  handleUnauthorized: () => void;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  principal: null,
  bootstrapped: false,
  pending: false,
  error: null,

  async checkSession() {
    try {
      const principal = await authRepo.session();
      set({ principal, bootstrapped: true });
    } catch {
      set({ principal: null, bootstrapped: true });
    }
  },

  async login(username, password) {
    set({ pending: true, error: null });
    try {
      const principal = await authRepo.login(username, password);
      set({ principal, pending: false, bootstrapped: true });
      return true;
    } catch (error) {
      set({ pending: false, error: messageOf(error), principal: null });
      return false;
    }
  },

  async logout() {
    try {
      await authRepo.logout();
    } finally {
      set({ principal: null, error: null });
    }
  },

  handleUnauthorized() {
    set({ principal: null, bootstrapped: true });
  },

  clearError() {
    set({ error: null });
  },
}));

setUnauthorizedHandler(() => useAuthStore.getState().handleUnauthorized());
