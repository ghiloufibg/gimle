import { create } from "zustand";
import { authRepo } from "@/repositories";
import type { Principal } from "@/types";

interface AuthState {
  principal: Principal | null;
  bootstrapped: boolean;
  pending: boolean;
  error: string | null;
  checkSession: () => Promise<void>;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  clearPrincipal: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  principal: null,
  bootstrapped: false,
  pending: false,
  error: null,
  checkSession: async () => {
    set({ pending: true });
    try {
      const principal = await authRepo.session();
      set({ principal, bootstrapped: true, pending: false, error: null });
    } catch (error) {
      set({
        principal: null,
        bootstrapped: true,
        pending: false,
        error: error instanceof Error ? error.message : "session check failed",
      });
    }
  },
  login: async (username, password) => {
    set({ pending: true, error: null });
    try {
      const principal = await authRepo.login(username, password);
      set({ principal, pending: false, bootstrapped: true, error: null });
      return true;
    } catch (error) {
      set({ pending: false, error: error instanceof Error ? error.message : "login failed" });
      return false;
    }
  },
  logout: async () => {
    set({ pending: true });
    try {
      await authRepo.logout();
    } finally {
      set({ principal: null, pending: false });
    }
  },
  clearPrincipal: () => set({ principal: null }),
}));
