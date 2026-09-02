import { describe, expect, it } from "vitest";

import {
  NO_FILTERS,
  UNTENANTED,
  compareApplications,
  filterApplications,
  kindLabelsOf,
  kindSlug,
  tenantsOf,
  totalsOf,
  type Application,
  type HealthStatus,
  type SyncStatus,
} from "@/addons/applications/model";

function app(over: Partial<Application> = {}): Application {
  const base: Application = {
    key: "deployment//orders",
    kind: "Deployment",
    kindLabel: "Deployment",
    name: "orders",
    tenantId: null,
    moduleId: { name: "orders", version: "1.0.0" },
    artifactPath: "",
    instances: [],
    services: [],
    health: "Healthy",
    sync: "Synced",
    conditions: [],
    detail: { type: "replicated", desiredReplicas: 1, unplacedCount: 0, requiredNodeLabels: [] },
  };
  return { ...base, ...over };
}

function named(name: string, health: HealthStatus, sync: SyncStatus = "Synced"): Application {
  return app({ name, health, sync, key: `deployment//${name}` });
}

describe("ordering", () => {
  it("puts what needs attention first, healthy last", () => {
    const sorted = [
      named("d", "Healthy"),
      named("c", "Unknown"),
      named("b", "Progressing"),
      named("a", "Degraded"),
    ].sort(compareApplications);
    expect(sorted.map((a) => a.health)).toEqual(["Degraded", "Progressing", "Unknown", "Healthy"]);
  });

  it("is stable between two polls that returned the same applications", () => {
    const one = named("orders", "Healthy");
    const two = app({
      name: "orders",
      kind: "StatefulSet",
      kindLabel: "StatefulSet",
      key: "statefulset//orders",
    });
    expect(compareApplications(one, two)).toBeLessThan(0);
    expect(compareApplications(two, one)).toBeGreaterThan(0);
  });
});

describe("filters", () => {
  const apps = [
    named("orders", "Degraded", "OutOfSync"),
    app({
      name: "hello-world",
      key: "custom.Greeting/acme/hello-world",
      kind: "CustomResource",
      kindLabel: "custom.Greeting",
      tenantId: "acme",
      moduleId: null,
      health: "Unknown",
      sync: "Unknown",
      detail: { type: "custom", generation: 1, observedGeneration: null, columns: [] },
    }),
  ];

  it("passes everything through when nothing is filtered", () => {
    expect(filterApplications(apps, NO_FILTERS)).toHaveLength(2);
  });

  it("filters by a custom kind's own name", () => {
    const kept = filterApplications(apps, { ...NO_FILTERS, kind: "custom.Greeting" });
    expect(kept.map((a) => a.name)).toEqual(["hello-world"]);
  });

  it("tells an untenanted application apart from every tenanted one", () => {
    const kept = filterApplications(apps, { ...NO_FILTERS, tenant: UNTENANTED });
    expect(kept.map((a) => a.name)).toEqual(["orders"]);
  });

  it("searches name, kind, module and tenant together", () => {
    expect(filterApplications(apps, { ...NO_FILTERS, search: "1.0.0" })).toHaveLength(1);
    expect(filterApplications(apps, { ...NO_FILTERS, search: "ACME" })).toHaveLength(1);
    expect(filterApplications(apps, { ...NO_FILTERS, search: "greeting" })).toHaveLength(1);
  });

  it("combines health and sync rather than choosing between them", () => {
    expect(filterApplications(apps, { ...NO_FILTERS, health: "Degraded", sync: "Synced" })).toEqual(
      [],
    );
  });
});

describe("summaries", () => {
  it("counts every application under exactly one health and one sync", () => {
    const totals = totalsOf([named("a", "Degraded", "OutOfSync"), named("b", "Healthy")]);
    expect(totals.health).toEqual({ Healthy: 1, Progressing: 0, Degraded: 1, Unknown: 0 });
    expect(totals.sync).toEqual({ Synced: 1, OutOfSync: 1, Unknown: 0 });
  });

  it("lists built-in kinds in declaration order, custom kinds after them", () => {
    const labels = kindLabelsOf([
      app({ kind: "CustomResource", kindLabel: "custom.Greeting" }),
      app({ kind: "CronJob", kindLabel: "CronJob" }),
      app({ kind: "Deployment", kindLabel: "Deployment" }),
    ]);
    expect(labels).toEqual(["Deployment", "CronJob", "custom.Greeting"]);
  });

  it("names the untenanted bucket explicitly in the tenant list", () => {
    expect(tenantsOf([named("a", "Healthy"), app({ tenantId: "acme" })])).toEqual([
      "acme",
      UNTENANTED,
    ]);
  });
});

describe("kind slugs", () => {
  it("addresses a built-in kind by a lowercase slug", () => {
    expect(kindSlug(app())).toBe("deployment");
    expect(kindSlug(app({ kind: "CronJob", kindLabel: "CronJob" }))).toBe("cronjob");
  });

  it("addresses a custom kind by its own name, so its URL reads as the kind", () => {
    expect(kindSlug(app({ kind: "CustomResource", kindLabel: "custom.Greeting" }))).toBe(
      "custom.Greeting",
    );
  });
});
