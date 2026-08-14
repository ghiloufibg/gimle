import { describe, expect, it } from "vitest";
import { MockArtifactsRepository } from "./artifacts";

describe("MockArtifactsRepository", () => {
  const repo = new MockArtifactsRepository();

  it("fetchCatalog returns module ids only, sorted, with no version detail attached", async () => {
    const catalog = await repo.fetchCatalog();

    expect(catalog.length).toBeGreaterThan(0);
    expect(catalog.every((id) => typeof id === "string")).toBe(true);
    expect([...catalog].sort()).toEqual(catalog);
    expect(catalog).toContain("greeter-provider");
  });

  it("fetchVersions returns fully-populated version rows for a catalogued module", async () => {
    const versions = await repo.fetchVersions("greeter-provider");

    expect(versions.length).toBeGreaterThan(1);
    for (const v of versions) {
      expect(Object.keys(v).sort()).toEqual([
        "moduleId",
        "pushedAtEpochMilli",
        "pushedBy",
        "sha256",
        "sizeBytes",
        "version",
      ]);
      expect(v.moduleId).toBe("greeter-provider");
      expect(v.sha256).toHaveLength(64);
      expect(v.sizeBytes).toBeGreaterThan(0);
      expect(v.pushedAtEpochMilli).toBeGreaterThan(0);
    }
  });

  it("each coordinate gets its own distinct digest, never one shared across versions", async () => {
    const versions = await repo.fetchVersions("billing-ledger");

    const digests = new Set(versions.map((v) => v.sha256));
    expect(digests.size).toBe(versions.length);
  });

  it("fetchVersions for an unknown module rejects, mirroring the registry's own 404", async () => {
    await expect(repo.fetchVersions("no-such-module")).rejects.toThrow(/no versions stored/);
  });

  it("remove drops just that one version, leaving the module's other versions stored", async () => {
    const before = await repo.fetchVersions("report-batch");
    expect(before.map((v) => v.version)).toContain("3.0.0");

    await repo.remove("report-batch", "3.0.0");

    const after = await repo.fetchVersions("report-batch");
    expect(after.map((v) => v.version)).not.toContain("3.0.0");
    expect(after).toHaveLength(before.length - 1);
  });

  it("removing a module's last version drops the module from the catalog entirely", async () => {
    expect(await repo.fetchCatalog()).toContain("edge-router");

    await repo.remove("edge-router", "0.9.0");

    expect(await repo.fetchCatalog()).not.toContain("edge-router");
  });

  it("remove of an unstored coordinate rejects rather than silently succeeding", async () => {
    await expect(repo.remove("greeter-consumer", "9.9.9")).rejects.toThrow(/no artifact stored/);
  });
});
