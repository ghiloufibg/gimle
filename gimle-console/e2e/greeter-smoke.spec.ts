import { test, expect } from "@playwright/test";

// Targets a real, already-running control plane with the greeter-provider and greeter-consumer
// sample modules deployed (gimle-examples/greeter-{provider,consumer}) -- see
// gimle-smoke-tests' GreeterSmokeTestIT, which brings up that cluster before running this suite,
// or gimle-console/LOCAL_DEV.md for doing the same by hand. Real browser, real backend, no mocked
// repositories: the point is proving the console reflects genuine deployed-module state.

// Matches gimle-smoke-tests' GreeterSmokeTestIT#SMOKE_OPERATOR_USERNAME/PASSWORD -- that test
// creates this account via an unauthenticated PUT /accounts/{username} before this suite runs
// (plaintext mode has no real security to protect: ApiServer#requireAuthorized bypasses auth
// entirely without TLS), so both sides just hardcode the same smoke-test-only credential rather
// than plumbing it through as config for a value that's never actually secret here.
const SMOKE_OPERATOR_USERNAME = "smoke-operator";
const SMOKE_OPERATOR_PASSWORD = "smoke-operator-password";

// The root route guard (__root.tsx) redirects any unauthenticated navigation to /console/login
// client-side -- every screen below is behind it, RBAC/session auth applying regardless of
// transport (unlike the server-side authorization check, which plaintext mode bypasses).
test.beforeEach(async ({ page }) => {
  await page.goto("/console/login");
  await page.getByLabel("Username").fill(SMOKE_OPERATOR_USERNAME);
  await page.getByLabel("Password").fill(SMOKE_OPERATOR_PASSWORD);
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page.getByRole("heading", { name: "Operator sign-in" })).toBeHidden();
});

// The deployments-detail screen fetches its deployment exactly once per page load (no
// auto-refresh) via a StoreClient read that round-robins across every gimle-mimir store replica
// (design doc §4.5 -- reads are deliberately not linearizable). A page load can land on a replica
// that hasn't yet caught up with a very recent write and would then show that stale state forever
// with no way to self-correct -- so this reloads the whole page on retry, not just re-checking the
// same already-rendered DOM, the same reason GreeterSmokeTestIT's own StoreClientClusterTest
// needed an equivalent same-poll-iteration read fix (see that test's `awaitPresent` helper).
async function expectDeploymentActive(page: import("@playwright/test").Page, name: string) {
  await expect(async () => {
    await page.goto(`/console/deployments/${name}`);
    await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
}

test("deployments screen shows both greeter deployments reaching Active", async ({ page }) => {
  await expectDeploymentActive(page, "greeter-provider-deployment");
  await expectDeploymentActive(page, "greeter-consumer-deployment");
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
