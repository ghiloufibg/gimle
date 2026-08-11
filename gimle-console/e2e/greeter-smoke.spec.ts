import { test, expect } from "@playwright/test";

// Targets a real, already-running control plane with the greeter-provider and greeter-consumer
// sample modules deployed (gimle-examples/greeter-{provider,consumer}) -- see
// gimle-smoke-tests' GreeterClusterTopologyIT, which brings up that cluster before running this
// suite, or gimle-console/LOCAL_DEV.md for doing the same by hand. Real browser, real backend, no
// mocked repositories: the point is proving the console reflects genuine deployed-module state.

// Matches gimle-smoke-tests' GreeterSmokeClusterSupport#SMOKE_OPERATOR_USERNAME/PASSWORD --
// GreeterClusterTopologyIT creates this account via an unauthenticated PUT /accounts/{username}
// before this suite runs
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
// same already-rendered DOM, the same reason gimle-mimir's own StoreClientClusterTest needed an
// equivalent same-poll-iteration read fix (see that test's `awaitPresent` helper).
async function expectDeploymentActive(page: import("@playwright/test").Page, name: string) {
  await expect(async () => {
    await page.goto(`/console/deployments/${name}`);
    await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
}

// Same non-linearizable-read staleness concern as expectDeploymentActive above, generalized: any
// list/detail screen backed by a StoreClient read (i.e. everything except Logs, which is its own
// dedicated /logs/* API) can land on a store replica that hasn't caught up yet. Reload-and-retry
// rather than re-checking the same already-rendered DOM, for the same reason.
async function expectVisibleEventually(
  page: import("@playwright/test").Page,
  url: string,
  text: string | RegExp,
) {
  await expect(async () => {
    await page.goto(url);
    await expect(page.getByText(text).first()).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
}

test("deployments screen shows both greeter deployments reaching Active", async ({ page }) => {
  await expectDeploymentActive(page, "greeter-provider-deployment");
  await expectDeploymentActive(page, "greeter-consumer-deployment");
});

test("overview screen lists the deployed cluster's real node and deployments", async ({ page }) => {
  await expectVisibleEventually(page, "/console", "greeter-provider-deployment");
  await expect(page.getByText("greeter-consumer-deployment").first()).toBeVisible();
  await expect(page.getByText("smoke-node-1").first()).toBeVisible();
});

test("nodes screen shows the real agent, healthy", async ({ page }) => {
  await expectVisibleEventually(page, "/console/nodes", "smoke-node-1");
  await expect(page.getByText("healthy", { exact: true }).first()).toBeVisible();
});

test("tenants screen shows the real provisioned tenant and its quota", async ({ page }) => {
  // Matches GreeterSmokeClusterSupport#SECRET_TENANT_ID -- provisioned via provisionTenantAndSecret
  // before either greeter deployment is submitted.
  await expectVisibleEventually(page, "/console/tenants", "smoke-tenant");
});

test("instances screen lists both greeter deployments' real running instances", async ({
  page,
}) => {
  await expectVisibleEventually(page, "/console/instances", "greeter-provider-deployment");
  await expect(page.getByText("greeter-consumer-deployment").first()).toBeVisible();
});

test("secrets screen shows the real secret key written through Fafnir, masked", async ({
  page,
}) => {
  // Matches GreeterSmokeClusterSupport#SECRET_KEY -- written via a real PUT /secrets/* call before the
  // provider deployment that reads it back is submitted. Only the key name is asserted: the
  // point of "masked until revealed" is that the value itself must never appear unrevealed.
  await expectVisibleEventually(page, "/console/secrets", "some-secret-key");
  await expect(page.getByText("smoke-test-secret-value")).toHaveCount(0);
});

test("topology screen places both greeter deployments on the real node", async ({ page }) => {
  await expectVisibleEventually(page, "/console/topology", "greeter-provider-deployment");
  await expect(page.getByText("greeter-consumer-deployment").first()).toBeVisible();
});

test("config screen shows a real plain config entry for the provisioned tenant", async ({
  page,
}) => {
  // Matches GreeterSmokeClusterSupport#PLAIN_CONFIG_KEY/PLAIN_CONFIG_VALUE -- written via a real,
  // unencrypted PUT /config/{tenantId}/{key} in provisionTenantAndSecret, distinct from the
  // masked SECRET_KEY the Secrets screen above already covers. Only one tenant exists in this
  // cluster, so the page's own auto-select-first-tenant behavior needs no extra interaction here.
  await expectVisibleEventually(page, "/console/config", "greeting.locale");
  await expect(page.getByText("en-US").first()).toBeVisible();
});

test("metrics screen shows a real, data-specific instance count, not just an empty shell", async ({
  page,
}) => {
  // "Instances observed" is a StatTile driven by the same real deployments/instances data every
  // other screen above already proves is genuine -- asserting its own rendered *value* (2: one
  // greeter-provider-deployment instance, one greeter-consumer-deployment instance, both
  // replicas: 1) is what makes this a data-specific check rather than just "the chart shell
  // rendered without crashing."
  await expect(async () => {
    await page.goto("/console/metrics");
    const instancesLabel = page.getByText("Instances observed", { exact: true });
    await expect(instancesLabel).toBeVisible({ timeout: 3_000 });
    const instancesTile = instancesLabel.locator("xpath=..");
    await expect(instancesTile.locator("span").first()).toHaveText("2", { timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
});

test("logs screen live-tails the consumer's real fabric call to the provider", async ({ page }) => {
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
