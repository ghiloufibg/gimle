// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { MemoryBytesField, MillicoresField } from "./fields";

afterEach(cleanup);

describe("MemoryBytesField", () => {
  it("keeps an invalid typed value (and its error) visible after blur, instead of reverting it", () => {
    const onChange = vi.fn();
    render(
      <MemoryBytesField label="Quota memory" bytes={1024 * 1024 * 1024} onChange={onChange} />,
    );
    const input = screen.getByLabelText("Quota memory") as HTMLInputElement;

    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "not-a-size" } });
    fireEvent.blur(input);

    expect(input.value).toBe("not-a-size");
    expect(screen.getByText(/Not a valid memory value/)).toBeTruthy();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("reformats to the canonical value once a valid value is typed and blurred", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <MemoryBytesField label="Quota memory" bytes={1024 * 1024 * 1024} onChange={onChange} />,
    );
    const input = screen.getByLabelText("Quota memory") as HTMLInputElement;

    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "2Gi" } });
    expect(onChange).toHaveBeenCalledWith(2 * 1024 * 1024 * 1024);

    rerender(
      <MemoryBytesField label="Quota memory" bytes={2 * 1024 * 1024 * 1024} onChange={onChange} />,
    );
    fireEvent.blur(input);

    expect(input.value).toBe("2Gi");
  });
});

describe("MillicoresField", () => {
  it("keeps an invalid typed value (and its error) visible after blur, instead of reverting it", () => {
    const onChange = vi.fn();
    render(<MillicoresField label="Quota cpu" value={4000} onChange={onChange} />);
    const input = screen.getByLabelText("Quota cpu") as HTMLInputElement;

    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "not-a-cpu" } });
    fireEvent.blur(input);

    expect(input.value).toBe("not-a-cpu");
    expect(screen.getByText(/Not a valid cpu value/)).toBeTruthy();
    expect(onChange).not.toHaveBeenCalled();
  });
});
