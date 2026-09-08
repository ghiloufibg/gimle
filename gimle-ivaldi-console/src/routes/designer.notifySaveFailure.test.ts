import { afterEach, describe, expect, it, vi } from "vitest";

const toastError = vi.fn();

vi.mock("sonner", () => ({ toast: { error: (...args: unknown[]) => toastError(...args) } }));

const { notifySaveFailure } = await import("./designer.$blueprintId");

describe("notifySaveFailure", () => {
  afterEach(() => toastError.mockReset());

  it("shows a visible, clearly-labeled error toast -- distinct from the conflict dialog's own restore/discard prompt", () => {
    notifySaveFailure();

    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError.mock.calls[0][0]).toBe("Save failed");
  });
});
