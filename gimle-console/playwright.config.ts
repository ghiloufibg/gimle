import { defineConfig, devices } from "@playwright/test";

// Deliberately no webServer block: this suite always targets an already-running real control
// plane (CONSOLE_BASE_URL, default a local dev cluster) -- bringing up a real cluster (control
// plane + agent + deployed modules) is GreeterSmokeTestIT's job (gimle-smoke-tests), not
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
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
