import { describe, expect, it } from "vitest";

import {
  MockArtifactsRepository,
  MockAuthRepository,
  MockStatusRepository,
} from "../mock/mockRepositories";
import { ApiError } from "../http/apiClient";

describe("MockAuthRepository", () => {
  it("returns an anonymous principal by default", async () => {
    const repo = new MockAuthRepository();
    await expect(repo.session()).resolves.toEqual({ username: "anonymous", groups: [] });
  });

  it("rejects empty credentials", async () => {
    const repo = new MockAuthRepository();
    await expect(repo.login("", "")).rejects.toBeInstanceOf(ApiError);
  });
});

describe("MockArtifactsRepository", () => {
  it("returns a sorted catalog of module ids", async () => {
    const repo = new MockArtifactsRepository();
    const catalog = await repo.catalog();
    expect(catalog.length).toBeGreaterThan(0);
    expect([...catalog].sort()).toEqual(catalog);
  });

  it("returns versions with the documented shape", async () => {
    const repo = new MockArtifactsRepository();
    const [first] = await repo.catalog();
    const entry = await repo.module(first!);
    expect(entry.moduleId).toBe(first);
    for (const version of entry.versions) {
      expect(version.sha256).toMatch(/^[0-9a-f]{64}$/);
      expect(version.sizeBytes).toBeGreaterThan(0);
      expect(typeof version.pushedBy).toBe("string");
    }
  });

  it("rejects re-pushing an existing version with 409", async () => {
    const repo = new MockArtifactsRepository();
    const [first] = await repo.catalog();
    const entry = await repo.module(first!);
    const existing = entry.versions[0]!;
    const file = new File(["x"], "a.jar");
    await expect(repo.push(first!, existing.version, file)).rejects.toMatchObject({ status: 409 });
  });

  it("reports module count for status", async () => {
    const artifacts = new MockArtifactsRepository();
    const status = await new MockStatusRepository(artifacts).status();
    expect(status.moduleCount).toBe(artifacts.moduleCount());
    expect(status.transportProtocol).toBe("PLAINTEXT");
  });
});
