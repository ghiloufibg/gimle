import { describe, expect, it } from "vitest";

import type { CustomResourceItem, KindDefinitionSummary } from "@/types";
import { fromCustomResource } from "@/addons/applications/kinds/custom";

function resource(overrides: Partial<CustomResourceItem> = {}): CustomResourceItem {
  return {
    kind: "custom.Greeting",
    name: "hello-world",
    tenantId: "acme",
    generation: 4,
    spec: { text: "hei" },
    status: { observedGeneration: 4 },
    ...overrides,
  };
}

const definition: KindDefinitionSummary = {
  kindName: "custom.Greeting",
  scope: "Tenant",
  description: "",
  names: { shortNames: [] },
  schema: { fields: [] },
  printColumns: [
    { name: "Text", path: "spec.text" },
    { name: "Said", path: "status.timesSaid" },
  ],
  generation: 1,
};

describe("custom resource health", () => {
  it("is healthy and synced once the operator has caught up", () => {
    const app = fromCustomResource(resource(), definition);
    expect([app.health, app.sync]).toEqual(["Healthy", "Synced"]);
    expect(app.conditions).toEqual([]);
  });

  it("is progressing and out of sync while the operator is behind the spec", () => {
    const app = fromCustomResource(resource({ status: { observedGeneration: 3 } }), definition);
    expect([app.health, app.sync]).toEqual(["Progressing", "OutOfSync"]);
    expect(app.conditions[0].message).toBe(
      "spec is at generation 4, the operator last reconciled 3",
    );
  });

  it("claims nothing when no operator has reported a status", () => {
    const app = fromCustomResource(resource({ status: null }), definition);
    expect([app.health, app.sync]).toEqual(["Unknown", "Unknown"]);
    expect(app.conditions).toEqual([]);
  });

  it("claims nothing for a status that does not carry the convention", () => {
    const app = fromCustomResource(resource({ status: { phase: "ok" } }), definition);
    expect([app.health, app.sync]).toEqual(["Unknown", "Unknown"]);
  });

  it("treats an operator ahead of the spec as caught up", () => {
    const app = fromCustomResource(resource({ status: { observedGeneration: 5 } }), definition);
    expect(app.health).toBe("Healthy");
  });

  it("resolves the kind's own print columns, missing paths included", () => {
    const app = fromCustomResource(resource(), definition);
    expect(app.detail.type === "custom" && app.detail.columns).toEqual([
      ["Text", "hei"],
      ["Said", "—"],
    ]);
  });

  it("names no module, since nothing ties a resource to the operator reconciling it", () => {
    const app = fromCustomResource(resource(), undefined);
    expect(app.moduleId).toBeNull();
    expect(app.kindLabel).toBe("custom.Greeting");
    expect(app.key).toBe("custom.Greeting/acme/hello-world");
  });
});
