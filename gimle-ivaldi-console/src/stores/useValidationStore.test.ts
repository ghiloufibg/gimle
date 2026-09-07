import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Blueprint, TenantData, WorkloadData } from "@/lib/blueprint";
import type { HilmirReport } from "@/repositories/contracts";

const validateMock = vi.fn();

vi.mock("@/repositories", () => ({
  blueprintsRepository: {
    mode: "http",
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    save: vi.fn(),
    delete: vi.fn(),
  },
  hilmirValidator: {
    mode: "http",
    baseUrl: undefined,
    validate: (...args: unknown[]) => validateMock(...args),
  },
}));

// Imported after the mock so the store picks up the mocked validator module.
const { useValidationStore } = await import("./useValidationStore");
const { createBlueprint, createNode } = await import("@/lib/blueprint");
const { renderFiles } = await import("@/lib/render");

function blueprintWithTwoTenantsSharingAWorkloadName(): {
  blueprint: Blueprint;
  webInTenantA: string;
  webInTenantB: string;
} {
  const blueprint = createBlueprint("test", { empty: true });
  const tenantA = createNode("tenant", { x: 0, y: 0 });
  (tenantA.data as TenantData).id = "tenant-a";
  const tenantB = createNode("tenant", { x: 0, y: 100 });
  (tenantB.data as TenantData).id = "tenant-b";
  const webA = createNode("deployment", { x: 100, y: 0 });
  (webA.data as WorkloadData).name = "web";
  (webA.data as { tenantId?: string }).tenantId = "tenant-a";
  const webB = createNode("deployment", { x: 100, y: 100 });
  (webB.data as WorkloadData).name = "web";
  (webB.data as { tenantId?: string }).tenantId = "tenant-b";
  blueprint.nodes = [tenantA, tenantB, webA, webB];
  return { blueprint, webInTenantA: webA.id, webInTenantB: webB.id };
}

function report(findings: HilmirReport["findings"]): HilmirReport {
  return { ok: false, validator: "hilmir", version: null, checkedAt: "", findings, error: null };
}

beforeEach(() => {
  validateMock.mockReset();
  useValidationStore.setState({
    problems: [],
    serverProblems: [],
    hilmir: {
      mode: "http",
      baseUrl: null,
      report: null,
      running: false,
      error: null,
      stale: false,
    },
  });
});

describe("useValidationStore.validateWithHilmir node attribution", () => {
  it("attributes a finding to the exact node its own file names, not just the first node sharing its name", async () => {
    const { blueprint, webInTenantB } = blueprintWithTwoTenantsSharingAWorkloadName();
    // The real file tenant B's "web" deployment renders to -- read off the same renderFiles call
    // the store itself makes, rather than hardcoded, so this test tracks the real naming scheme.
    const fileForTenantBsWeb = renderFiles(blueprint).find((f) => f.nodeId === webInTenantB)!.path;
    validateMock.mockResolvedValue(
      report([
        {
          code: "SOME_FINDING",
          severity: "error",
          message: "something about tenant b's web",
          file: fileForTenantBsWeb,
          resource: "Deployment/web",
        },
      ]),
    );

    await useValidationStore.getState().validateWithHilmir(blueprint);

    expect(useValidationStore.getState().serverProblems[0].nodeId).toBe(webInTenantB);
  });

  it("falls back to name matching only when the finding names no file", async () => {
    const { blueprint, webInTenantA } = blueprintWithTwoTenantsSharingAWorkloadName();
    validateMock.mockResolvedValue(
      report([
        {
          code: "SOME_FINDING",
          severity: "error",
          message: "no file on this one",
          resource: "Deployment/web",
        },
      ]),
    );

    await useValidationStore.getState().validateWithHilmir(blueprint);

    // The first node of that name on the canvas -- the same fallback behavior as before, kept
    // only for a finding with nothing more specific to go on.
    expect(useValidationStore.getState().serverProblems[0].nodeId).toBe(webInTenantA);
  });
});
