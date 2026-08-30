import { describe, expect, it } from "vitest";
import { MockCustomResourcesRepository } from "./customResources";

describe("MockCustomResourcesRepository", () => {
  const repo = new MockCustomResourcesRepository();

  it("fetchKinds returns fully-populated definitions with schema and printColumns", async () => {
    const kinds = await repo.fetchKinds();

    expect(kinds.length).toBeGreaterThan(1);
    const greeting = kinds.find((k) => k.kindName === "custom.Greeting");
    expect(greeting).toBeDefined();
    expect(greeting!.scope).toBe("Tenant");
    expect(greeting!.names.plural).toBe("greetings");
    expect(greeting!.names.shortNames).toContain("gr");
    expect(greeting!.schema.fields.map((f) => f.name)).toEqual(["message", "repeat", "tone"]);
    expect(greeting!.printColumns.map((c) => c.path)).toEqual(["spec.message", "status.timesSaid"]);
  });

  it("every kind name carries the mandatory dot prefix", async () => {
    const kinds = await repo.fetchKinds();

    for (const kind of kinds) {
      expect(kind.kindName).toMatch(/\./);
    }
  });

  it("fetchResources returns instances whose spec is fully defaulted", async () => {
    const resources = await repo.fetchResources("custom.Greeting");

    expect(resources.length).toBeGreaterThan(0);
    for (const resource of resources) {
      expect(resource.kind).toBe("custom.Greeting");
      // Admission persists defaults, so a stored spec always carries every defaulted field.
      expect(resource.spec).toHaveProperty("tone");
    }
  });

  it("a resource no operator has reconciled yet carries a null status, never an empty object", async () => {
    const resources = await repo.fetchResources("custom.Greeting");

    const unreported = resources.find((r) => r.name === "goodbye");
    expect(unreported).toBeDefined();
    expect(unreported!.status).toBeNull();
  });

  it("a Cluster-scoped kind's instances carry no tenantId", async () => {
    const resources = await repo.fetchResources("acme.AlertRule");

    expect(resources.length).toBeGreaterThan(0);
    for (const resource of resources) {
      expect(resource.tenantId).toBeUndefined();
    }
  });

  it("fetchResources for an unknown kind rejects, mirroring the server's 400", async () => {
    await expect(repo.fetchResources("custom.NoSuchKind")).rejects.toThrow(/no such kind/);
  });
});
