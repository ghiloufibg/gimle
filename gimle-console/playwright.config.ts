import { existsSync } from "node:fs";
import { defineConfig, devices } from "@playwright/test";

// Deliberately no webServer block: this suite always targets an already-running real control
// plane (CONSOLE_BASE_URL, default a local dev cluster) -- bringing up a real cluster (control
// plane + agent + deployed modules) is GreeterClusterTopologyIT's job (gimle-smoke-tests), not
// something a frontend-only tool should own.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: process.env.CONSOLE_BASE_URL ?? "http://127.0.0.1:8080",
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // The sandbox this suite runs in (see gimle-smoke-tests' GreeterSmokeClusterSupport)
        // pre-installs Chromium at a fixed path rather than letting Playwright auto-download
        // the revision its own pinned
        // @playwright/test version expects -- without this, browserType.launch fails looking for
        // a revision-specific executable that was never fetched. Harmless outside that sandbox:
        // any host with this path present (or PLAYWRIGHT_BROWSERS_PATH pointed at one providing
        // it) gets the same pre-installed browser; only a host with neither falls back to
        // Playwright's own default resolution, which continues to work unchanged.
        launchOptions: existsSync("/opt/pw-browsers/chromium")
          ? { executablePath: "/opt/pw-browsers/chromium" }
          : {},
      },
    },
  ],
});
