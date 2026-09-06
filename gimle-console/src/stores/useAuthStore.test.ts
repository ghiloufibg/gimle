import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  authRepo: { login: vi.fn(), logout: vi.fn(), session: vi.fn() },
}));

import { authRepo } from "@/repositories";
import { ApiError } from "@/repositories/http/apiClient";
import { useAuthStore } from "./useAuthStore";

describe("useAuthStore", () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: "unknown",
      principal: null,
      error: null,
      sessionExpired: false,
      initialized: false,
    });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("login failure surfaces a generic error and leaves status unauthenticated", async () => {
    vi.mocked(authRepo.login).mockRejectedValueOnce(new Error("invalid username or password"));

    await useAuthStore.getState().login("admin", "wrong");

    const state = useAuthStore.getState();
    expect(state.status).toBe("unauthenticated");
    expect(state.principal).toBeNull();
    expect(state.error).toBe("invalid username or password");
  });

  it("a successful login sets status authenticated and clears any previous error", async () => {
    useAuthStore.setState({ error: "stale previous error" });
    const principal = { username: "admin", groups: ["gimle:operators"] };
    vi.mocked(authRepo.login).mockResolvedValueOnce(principal);

    await useAuthStore.getState().login("admin", "correct-password");

    const state = useAuthStore.getState();
    expect(state.status).toBe("authenticated");
    expect(state.principal).toEqual(principal);
    expect(state.error).toBeNull();
  });

  it("init() with no existing session sets status unauthenticated", async () => {
    vi.mocked(authRepo.session).mockResolvedValueOnce(null);

    await useAuthStore.getState().init();

    expect(useAuthStore.getState().status).toBe("unauthenticated");
  });

  it("a session probe the control plane refused is not read as a signed-out operator", async () => {
    // 429 is "ask again shortly". Landing it as "unauthenticated" bounces the router to /login --
    // in plaintext mode, a sign-in screen for a credential the operator does not have.
    vi.useFakeTimers();
    try {
      vi.mocked(authRepo.session).mockRejectedValueOnce(
        new ApiError(429, "control plane at capacity; retry shortly"),
      );

      await useAuthStore.getState().init();

      const state = useAuthStore.getState();
      expect(state.status).toBe("unknown");
      expect(state.status).not.toBe("unauthenticated");
    } finally {
      vi.useRealTimers();
    }
  });

  it("re-probes after a refused probe and settles on the principal the retry returns", async () => {
    vi.useFakeTimers();
    try {
      const anonymous = { username: "anonymous", groups: [], anonymous: true };
      vi.mocked(authRepo.session)
        .mockRejectedValueOnce(new ApiError(429, "at capacity"))
        .mockResolvedValueOnce(anonymous);

      await useAuthStore.getState().init();
      await vi.advanceTimersByTimeAsync(2_000);

      const state = useAuthStore.getState();
      expect(state.principal).toEqual(anonymous);
      expect(state.status).toBe("authenticated");
    } finally {
      vi.useRealTimers();
    }
  });

  it("stops re-probing rather than asking forever", async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(authRepo.session).mockRejectedValue(new ApiError(429, "at capacity"));

      await useAuthStore.getState().init();
      await vi.advanceTimersByTimeAsync(60_000);

      expect(authRepo.session).toHaveBeenCalledTimes(3);
      expect(useAuthStore.getState().status).toBe("unknown");
    } finally {
      vi.useRealTimers();
    }
  });

  it("init() only calls session() once even if invoked twice", async () => {
    vi.mocked(authRepo.session).mockResolvedValue(null);

    await useAuthStore.getState().init();
    await useAuthStore.getState().init();

    expect(authRepo.session).toHaveBeenCalledTimes(1);
  });

  it("handleUnauthorized clears principal and sets status unauthenticated", () => {
    useAuthStore.setState({
      status: "authenticated",
      principal: { username: "admin", groups: [] },
    });

    useAuthStore.getState().handleUnauthorized();

    const state = useAuthStore.getState();
    expect(state.status).toBe("unauthenticated");
    expect(state.principal).toBeNull();
  });

  it("a 401 under a signed-in operator records the session as expired", () => {
    useAuthStore.setState({
      status: "authenticated",
      principal: { username: "admin", groups: [] },
    });

    useAuthStore.getState().handleUnauthorized();

    expect(useAuthStore.getState().sessionExpired).toBe(true);
  });

  it("a 401 with no session yet is a first visit, not an expiry", () => {
    useAuthStore.getState().handleUnauthorized();

    expect(useAuthStore.getState().sessionExpired).toBe(false);
  });

  it("a 401 under plaintext mode's anonymous principal is not an expiry either", () => {
    useAuthStore.setState({
      status: "authenticated",
      principal: { username: "anonymous", groups: [], anonymous: true },
    });

    useAuthStore.getState().handleUnauthorized();

    expect(useAuthStore.getState().sessionExpired).toBe(false);
  });

  it("signing back in clears the expiry notice", async () => {
    useAuthStore.setState({ sessionExpired: true });
    vi.mocked(authRepo.login).mockResolvedValueOnce({ username: "admin", groups: [] });

    await useAuthStore.getState().login("admin", "correct-password");

    expect(useAuthStore.getState().sessionExpired).toBe(false);
  });

  it("a deliberate sign-out is not reported as an expiry", async () => {
    useAuthStore.setState({
      status: "authenticated",
      principal: { username: "admin", groups: [] },
      sessionExpired: true,
    });
    vi.mocked(authRepo.logout).mockResolvedValueOnce(undefined);

    await useAuthStore.getState().logout();

    expect(useAuthStore.getState().sessionExpired).toBe(false);
  });
});
