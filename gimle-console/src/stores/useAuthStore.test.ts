import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/repositories", () => ({
  authRepo: { login: vi.fn(), logout: vi.fn(), session: vi.fn() },
}));

import { authRepo } from "@/repositories";
import { useAuthStore } from "./useAuthStore";

describe("useAuthStore", () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: "unknown",
      principal: null,
      error: null,
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
});
