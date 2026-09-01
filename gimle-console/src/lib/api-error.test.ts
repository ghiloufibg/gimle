import { afterEach, describe, expect, it, vi } from "vitest";

import { describeApiError, isSessionExpired, notifyApiError, storeErrorMessage } from "./api-error";
import {
  ApiError,
  SESSION_EXPIRED_MESSAGE,
  SessionExpiredError,
} from "@/repositories/http/apiClient";

const errorToast = vi.fn();
vi.mock("sonner", () => ({ toast: { error: (m: string) => errorToast(m) } }));

afterEach(() => {
  errorToast.mockClear();
});

describe("isSessionExpired", () => {
  it("recognises the 401 the api client throws", () => {
    expect(isSessionExpired(new SessionExpiredError("not authenticated"))).toBe(true);
  });

  it("does not treat a 403 as an expired session -- that caller is signed in", () => {
    expect(isSessionExpired(new ApiError(403, "forbidden"))).toBe(false);
  });

  it("does not treat an arbitrary failure as an expired session", () => {
    expect(isSessionExpired(new Error("network down"))).toBe(false);
  });
});

describe("describeApiError", () => {
  it("describes an expired session in plain language, never as a status line", () => {
    const message = describeApiError(new SessionExpiredError("not authenticated"));
    expect(message).toBe(SESSION_EXPIRED_MESSAGE);
    expect(message).not.toMatch(/401/);
  });

  it("describes a bare 403 as a missing permission, dropping the useless body", () => {
    expect(describeApiError(new ApiError(403, "forbidden"))).toBe(
      "You don't have permission to do that.",
    );
  });

  it("keeps a 403 body that actually explains the denial", () => {
    expect(
      describeApiError(new ApiError(403, "gimle-system is reserved for gimle:operators-group")),
    ).toBe(
      "You don't have permission to do that. (gimle-system is reserved for gimle:operators-group)",
    );
  });

  it("passes any other API failure through with its own detail intact", () => {
    expect(describeApiError(new ApiError(500, "scheduler unavailable"))).toBe(
      "control plane responded 500: scheduler unavailable",
    );
  });

  it("handles a plain Error and a non-Error rejection", () => {
    expect(describeApiError(new Error("network down"))).toBe("network down");
    expect(describeApiError("nope")).toBe("nope");
  });
});

describe("notifyApiError", () => {
  it("shows nothing for an expired session -- the sign-in screen is the one explanation", () => {
    expect(notifyApiError(new SessionExpiredError("not authenticated"))).toBe(false);
    expect(errorToast).not.toHaveBeenCalled();
  });

  it("still surfaces a 403 in place, in plain language", () => {
    expect(notifyApiError(new ApiError(403, "forbidden"))).toBe(true);
    expect(errorToast).toHaveBeenCalledWith("You don't have permission to do that.");
  });

  it("surfaces any other failure with its own detail", () => {
    expect(notifyApiError(new ApiError(409, "conflict"))).toBe(true);
    expect(errorToast).toHaveBeenCalledWith("control plane responded 409: conflict");
  });
});

describe("storeErrorMessage", () => {
  it("keeps an expired session out of a screen's inline error banner", () => {
    expect(storeErrorMessage(new SessionExpiredError("not authenticated"))).toBeNull();
  });

  it("keeps a permission failure in the banner, where the operator still is", () => {
    expect(storeErrorMessage(new ApiError(403, "forbidden"))).toBe(
      "You don't have permission to do that.",
    );
  });
});
