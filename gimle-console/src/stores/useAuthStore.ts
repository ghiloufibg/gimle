import { create } from "zustand";
import type { Principal } from "@/types";
import { authRepo } from "@/repositories";
import { setUnauthorizedHandler } from "@/repositories/http/apiClient";

interface AuthState {
  status: "unknown" | "authenticated" | "unauthenticated";
  principal: Principal | null;
  error: string | null;
  /** True only when a real signed-in session lapsed under the operator, which is what lets /login
   * tell a bounced visit apart from a first one and say so. */
  sessionExpired: boolean;
  initialized: boolean;
  init(): Promise<void>;
  login(username: string, password: string): Promise<boolean>;
  logout(): Promise<void>;
  handleUnauthorized(): void;
}

/** In plaintext mode /auth/session hands back a synthetic anonymous principal before anyone signs
 * in, so "there is a principal" is not the same question as "an operator signed in". */
function isSignedIn(principal: Principal | null): boolean {
  return principal !== null && !principal.anonymous;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  status: "unknown",
  principal: null,
  error: null,
  sessionExpired: false,
  initialized: false,
  async init() {
    if (get().initialized) return;
    set({ initialized: true });
    try {
      const principal = await authRepo.session();
      set({
        principal,
        status: principal ? "authenticated" : "unauthenticated",
      });
    } catch {
      set({ principal: null, status: "unauthenticated" });
    }
  },
  async login(username, password) {
    set({ error: null });
    try {
      const principal = await authRepo.login(username, password);
      set({ principal, status: "authenticated", error: null, sessionExpired: false });
      return true;
    } catch (e) {
      set({
        principal: null,
        status: "unauthenticated",
        error: e instanceof Error ? e.message : "login failed",
      });
      return false;
    }
  },
  async logout() {
    try {
      await authRepo.logout();
    } finally {
      // A deliberate sign-out is not an expiry: the operator knows perfectly well why they are
      // looking at the sign-in screen, so /login must not tell them their session lapsed.
      set({ principal: null, status: "unauthenticated", error: null, sessionExpired: false });
    }
  },
  /** Called by apiClient.ts's send() on any 401 -- a session expiring mid-use (or never having
   * existed) clears local auth state so the root route guard redirects to /login. The expiry flag
   * is set only when there was a real session to lose: a 401 from the very first /auth/session
   * probe, or from submitting the wrong password, is not an expiry and must not be described as
   * one on the screen the operator is already looking at. */
  handleUnauthorized() {
    set({
      principal: null,
      status: "unauthenticated",
      sessionExpired: isSignedIn(get().principal),
    });
  },
}));

setUnauthorizedHandler(() => useAuthStore.getState().handleUnauthorized());
