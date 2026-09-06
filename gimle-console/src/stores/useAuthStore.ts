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

/** How long to wait before asking again after a session probe the control plane didn't answer.
 * Bounded, and deliberately longer than apiClient's own per-request retry of a 429: a control
 * plane still refusing after all of that has something wrong with it that asking again won't fix,
 * and every screen surfaces its own failure meanwhile. */
const PROBE_RETRY_DELAYS_MS = [2_000, 8_000];

export const useAuthStore = create<AuthState>((set, get) => {
  /**
   * Reads who the caller is, once. A probe that comes back {@code null} is a real answer -- nobody
   * is signed in -- and lands as "unauthenticated", which the router guard bounces to /login. A
   * probe that *fails* is not an answer at all: a control plane refusing the read (a 429 outliving
   * apiClient's own retries, say, which a console page-load's burst of requests can provoke) or one
   * that couldn't be reached has said nothing about who the caller is, and in plaintext mode there
   * is no sign-in screen to send them to and no credential to sign in with. That leaves the status
   * "unknown", which the guard does not act on, and asks again.
   */
  async function probeSession(attempt: number): Promise<void> {
    try {
      const principal = await authRepo.session();
      set({
        principal,
        status: principal ? "authenticated" : "unauthenticated",
      });
    } catch {
      set({ principal: null, status: "unknown" });
      const delay = PROBE_RETRY_DELAYS_MS[attempt];
      if (delay === undefined) return;
      setTimeout(() => {
        void probeSession(attempt + 1);
      }, delay);
    }
  }

  return {
    status: "unknown",
    principal: null,
    error: null,
    sessionExpired: false,
    initialized: false,
    async init() {
      if (get().initialized) return;
      set({ initialized: true });
      await probeSession(0);
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
  };
});

setUnauthorizedHandler(() => useAuthStore.getState().handleUnauthorized());
