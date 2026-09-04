import { describe, expect, it } from "vitest";

import type { Blueprint, StoreData, TenantData } from "./blueprint";
import { sampleBlueprints } from "./samples";
import { validate } from "./rules";

const [ordersPlatform, brokenExample] = sampleBlueprints();

function codesOf(bp: Blueprint): string[] {
  return validate(bp).map((p) => p.code);
}

function clone(bp: Blueprint): Blueprint {
  return structuredClone(bp);
}

describe("the clean sample", () => {
  it("validates with only shape advisories: single-machine warnings and the secret info note, no errors", () => {
    const problems = validate(ordersPlatform!);
    expect(problems.some((p) => p.severity === "error")).toBe(false);
    expect(problems.map((p) => p.code).sort()).toEqual(
      [
        "AGENTS_COLOCATED",
        "AGENTS_COLOCATED",
        "SECRET_NO_VALUE_AT_RUN",
        "SINGLE_CONTROL_PLANE",
        "SINGLE_STORE",
      ].sort(),
    );
  });
});

describe("the broken sample", () => {
  it("surfaces the errors its shape was built to exercise", () => {
    const codes = codesOf(brokenExample!);
    expect(codes).toContain("PORT_CONFLICT"); // two control planes on one machine, same port
    expect(codes).toContain("SERVICE_TARGET_MISSING"); // fronts a deployment that doesn't exist
    expect(codes).toContain("RESOURCES_REQUEST_OVER_LIMIT"); // request 512Mi/900m > limit 128Mi/200m
    expect(codes).toContain("MTLS_IP_LITERAL_HOST"); // mtls transport, host is an IP literal
  });
});

describe("topology rules", () => {
  it("flags every required section missing on an empty blueprint", () => {
    const empty: Blueprint = clone(ordersPlatform!);
    empty.nodes = [];
    empty.edges = [];
    const codes = codesOf(empty);
    expect(codes).toEqual(
      expect.arrayContaining(["NO_MACHINES", "NO_STORE", "NO_CONTROL_PLANE", "NO_FAFNIR"]),
    );
  });

  it("flags a role placed on a machine that doesn't exist", () => {
    const bp = clone(ordersPlatform!);
    const store = bp.nodes.find((n) => n.kind === "store")!;
    (store.data as StoreData & { machine: string }).machine = "no-such-machine";
    expect(codesOf(bp)).toContain("UNKNOWN_MACHINE");
  });

  it("escalates colocation from a warning to an error once more than one machine exists", () => {
    const oneMachine = clone(ordersPlatform!);
    const secondCp = structuredClone(oneMachine.nodes.find((n) => n.kind === "controlPlane")!);
    secondCp.id = "r-cp-2";
    oneMachine.nodes.push(secondCp);
    const oneMachineProblems = validate(oneMachine).filter((p) => p.code === "REPLICAS_COLOCATED");
    expect(oneMachineProblems.every((p) => p.severity === "warning")).toBe(true);

    const twoMachines = clone(oneMachine);
    twoMachines.nodes.push({
      id: "m-2",
      kind: "machine",
      position: { x: 0, y: 0 },
      data: { name: "second", host: "127.0.0.1" },
    });
    const twoMachineProblems = validate(twoMachines).filter((p) => p.code === "REPLICAS_COLOCATED");
    expect(twoMachineProblems.length).toBeGreaterThan(0);
    expect(twoMachineProblems.every((p) => p.severity === "error")).toBe(true);
  });
});

describe("application rules", () => {
  it("counts a deployment's instances as replicas plus maxSurge against tenant quota", () => {
    const bp = clone(ordersPlatform!);
    const tenant = bp.nodes.find((n) => n.kind === "tenant")!;
    (tenant.data as TenantData).quota = {
      maxMemoryBytes: 1,
      maxCpuMillicores: 1,
      maxInstances: 1,
    };
    expect(codesOf(bp)).toContain("QUOTA_EXCEEDED");
  });

  it("flags a resource belonging to no known tenant", () => {
    const bp = clone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    bp.edges = bp.edges.filter((e) => e.source !== deployment.id);
    (deployment.data as { tenantId?: string }).tenantId = "no-such-tenant";
    expect(codesOf(bp)).toContain("TENANT_UNKNOWN");
  });

  it("flags a cron schedule that doesn't have exactly 5 fields", () => {
    const bp = clone(ordersPlatform!);
    const cron = bp.nodes.find((n) => n.kind === "cronJob")!;
    (cron.data as { schedule?: string }).schedule = "not a schedule";
    expect(codesOf(bp)).toContain("CRON_SCHEDULE_INVALID");
  });
});
