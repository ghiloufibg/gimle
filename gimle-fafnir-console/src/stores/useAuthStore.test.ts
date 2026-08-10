import { afterEach, describe, expect, it, vi } from "vitest";

import { useAuthStore } from "./useAuthStore";

// The composition root (@/repositories) always wires the Http implementation (see
// src/repositories/index.ts's own javadoc-equivalent comment), so this store-level test stubs
// fetch directly rather than swapping in the Mock repository -- the same boundary
// ConsoleLogEncoderTest-style tests exercise on the Java side of this project.
afterEach(() => {
  vi.unstubAllGlobals();
  useAuthStore.setState({ principal: null, bootstrapped: false, pending: false, error: null });
});

describe("useAuthStore", () => {
  it("a failed login surfaces the server's error message and clears the principal", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("invalid username or password", { status: 401 })),
    );

    const ok = await useAuthStore.getState().login("admin", "wrong");

    expect(ok).toBe(false);
    expect(useAuthStore.getState().principal).toBeNull();
    expect(useAuthStore.getState().error).toBe("invalid username or password");
  });

  it("handleUnauthorized clears the principal even mid-session", () => {
    useAuthStore.setState({ principal: { username: "admin", groups: [] }, bootstrapped: true });

    useAuthStore.getState().handleUnauthorized();

    expect(useAuthStore.getState().principal).toBeNull();
    expect(useAuthStore.getState().bootstrapped).toBe(true);
  });

  it("checkSession with no cookie leaves the principal null but marks bootstrapped", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("not authenticated", { status: 401 })),
    );

    await useAuthStore.getState().checkSession();

    expect(useAuthStore.getState().principal).toBeNull();
    expect(useAuthStore.getState().bootstrapped).toBe(true);
  });
});
