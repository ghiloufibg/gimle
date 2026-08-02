import { test, expect } from "@playwright/test";

// Targets a real, already-running control plane with the greeter-provider and greeter-consumer
// sample modules deployed (gimle-examples/greeter-{provider,consumer}) -- see
// gimle-smoke-tests' GreeterSmokeTestIT, which brings up that cluster before running this suite,
// or gimle-console/LOCAL_DEV.md for doing the same by hand. Real browser, real backend, no mocked
// repositories: the point is proving the console reflects genuine deployed-module state.

test("deployments screen shows both greeter deployments reaching Active", async ({ page }) => {
  await page.goto("/console/deployments/greeter-provider-deployment");
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible({ timeout: 30_000 });

  await page.goto("/console/deployments/greeter-consumer-deployment");
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible({ timeout: 30_000 });
});

test("logs screen live-tails the consumer's real fabric call to the provider", async ({
  page,
}) => {
  await page.goto(
    "/console/logs?kind=instance&deploymentName=greeter-consumer-deployment&instanceIndex=0&category=APPLICATION",
  );

  // The consumer calls greeter-provider every 5s and logs the reply -- give follow mode a couple
  // of intervals to pick up a real line rather than asserting only on whatever loaded initially.
  await page.getByRole("button", { name: /Follow/i }).click();
  await expect(page.getByText("Hello, Gimlé!", { exact: false }).first()).toBeVisible({
    timeout: 30_000,
  });
});
