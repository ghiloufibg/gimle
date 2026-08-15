import { create } from "zustand";
import type { Principal } from "@/types";
import { authRepo } from "@/repositories";
import { setUnauthorizedHandler } from "@/repositories/http/apiClient";

interface AuthState {
  status: "unknown" | "authenticated" | "unauthenticated";
  principal: Principal | null;
  error: string | null;
  initialized: boolean;
  init(): Promise<void>;
  login(username: string, password: string): Promise<boolean>;
  logout(): Promise<void>;
  handleUnauthorized(): void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  status: "unknown",
  principal: null,
  error: null,
  initialized: false,
  async init() {
    if (get().initialized) return;
    set({ initialized: true });
    const principal = await authRepo.session();
    set({
      principal,
      status: principal ? "authenticated" : "unauthenticated",
    });
  },
  async login(username, password) {
    set({ error: null });
    try {
      const principal = await authRepo.login(username, password);
      set({ principal, status: "authenticated", error: null });
      return true;
    } catch {
      set({
        principal: null,
        status: "unauthenticated",
        error: "invalid username or password",
      });
      return false;
    }
  },
  async logout() {
    await authRepo.logout();
    set({ principal: null, status: "unauthenticated", error: null });
  },
  /** Called by apiClient.ts's send() on any 401 -- a session expiring mid-use (or never having
   * existed) clears local auth state so the root route guard redirects to /login. */
  handleUnauthorized() {
    set({ principal: null, status: "unauthenticated" });
  },
}));

setUnauthorizedHandler(() => useAuthStore.getState().handleUnauthorized());
