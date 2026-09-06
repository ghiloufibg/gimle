import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { CopyButton } from "./CopyButton";

// Rendered to static markup rather than into a DOM: the accessible name is an attribute of the
// initial render, which react-dom/server produces on its own under this project's node-environment
// vitest config.

describe("CopyButton", () => {
  it("names the action it will perform, not one it hasn't performed yet", () => {
    const html = renderToStaticMarkup(<CopyButton value="deadbeef" subject="sha256" />);
    expect(html).toContain('aria-label="Copy sha256"');
    expect(html).not.toContain("copied");
  });
});
