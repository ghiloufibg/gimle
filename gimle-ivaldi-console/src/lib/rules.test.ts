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
      ["AGENTS_COLOCATED", "SECRET_NO_VALUE_AT_RUN", "SINGLE_CONTROL_PLANE", "SINGLE_STORE"].sort(),
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
    bp.edges = bp.edges.filter((e) => !(e.kind === "placedOn" && e.source === store.id));
    (store.data as StoreData & { machine: string }).machine = "no-such-machine";
    const problem = validate(bp).find((p) => p.code === "UNKNOWN_MACHINE");
    expect(problem?.message).toContain("no-such-machine");
  });

  /**
   * The edge is the live link and the text field is a copy taken when it was typed, so renaming a
   * machine has to follow the edges that are still drawn to it rather than stranding every role on
   * a name that no longer exists.
   */
  it("resolves a role's machine through its edge, so renaming the machine doesn't strand it", () => {
    const bp = clone(ordersPlatform!);
    const machine = bp.nodes.find((n) => n.kind === "machine")!;
    (machine.data as { name: string }).name = "renamed";
    expect(codesOf(bp)).not.toContain("UNKNOWN_MACHINE");
  });

  it("names the duplicated agent node id, which only the live tier can see before a parse", () => {
    const bp = clone(ordersPlatform!);
    const agents = bp.nodes.filter((n) => n.kind === "agent");
    (agents[1].data as { nodeId: string }).nodeId = (agents[0].data as { nodeId: string }).nodeId;
    const problem = validate(bp).find((p) => p.code === "DUPLICATE_NODE_ID");
    expect(problem?.message).toContain((agents[0].data as { nodeId: string }).nodeId);
  });

  it("reports an unset or out-of-range port as itself, not as a conflict", () => {
    const bp = clone(ordersPlatform!);
    const cp = bp.nodes.find((n) => n.kind === "controlPlane")!;
    (cp.data as { port?: number }).port = undefined;
    expect(codesOf(bp)).toContain("PORT_UNSET");
    (cp.data as { port?: number }).port = 70000;
    const codes = codesOf(bp);
    expect(codes).toContain("PORT_RANGE");
    expect(codes).not.toContain("PORT_UNSET");
  });

  it("checks the agent's gossip port too, not only the roles with a 'port' field", () => {
    const bp = clone(ordersPlatform!);
    (bp.nodes.find((n) => n.kind === "agent")!.data as { gossipPort?: number }).gossipPort = 70000;
    expect(codesOf(bp)).toContain("PORT_RANGE");
  });

  it("reports colocation once per group rather than once per member", () => {
    const bp = clone(ordersPlatform!);
    const agentProblems = validate(bp).filter((p) => p.code === "AGENTS_COLOCATED");
    expect(agentProblems).toHaveLength(1);
  });

  it("warns that jvm flags are per role once two replicas of one role disagree", () => {
    const bp = clone(ordersPlatform!);
    const stores = bp.nodes.filter((n) => n.kind === "store");
    const second = structuredClone(stores[0]);
    second.id = "r-store-2";
    (stores[0].data as { jvmFlags?: string[] }).jvmFlags = ["-Xmx512m"];
    (second.data as { jvmFlags?: string[] }).jvmFlags = ["-Xmx99m"];
    bp.nodes.push(second);
    expect(codesOf(bp)).toContain("JVM_FLAGS_PER_ROLE");
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

describe("application rules the two tiers used to disagree on", () => {
  it("does not fault a limit range whose bounds are still empty", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = { ...lr.data, min: { memory: "", cpu: "" }, max: { memory: "", cpu: "" } };
    const codes = codesOf(bp);
    expect(codes).not.toContain("LIMITRANGE_VIOLATION");
    expect(codes).toContain("LIMITRANGE_NO_BOUNDS");
  });

  it("still faults a request outside a partially-filled limit range", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = { ...lr.data, min: { memory: "8Gi", cpu: "" }, max: { memory: "", cpu: "" } };
    expect(codesOf(bp)).toContain("LIMITRANGE_VIOLATION");
  });

  it("refuses a jar-sourced workload with no registry to push it to", () => {
    const bp = clone(ordersPlatform!);
    bp.nodes = bp.nodes.filter((n) => n.kind !== "andvari");
    const workload = bp.nodes.find((n) => n.kind === "deployment")!;
    workload.data = { ...workload.data, artifact: { source: "jar", path: "/tmp/x.jar" } };
    expect(codesOf(bp)).toContain("NO_ANDVARI_FOR_JAR");
  });

  it("refuses a second tenant under plaintext, where the control plane cannot tell them apart", () => {
    const bp = clone(ordersPlatform!);
    const tenant = structuredClone(bp.nodes.find((n) => n.kind === "tenant")!);
    tenant.id = "t-second";
    tenant.data = { ...tenant.data, id: "second-tenant" };
    bp.nodes.push(tenant);
    expect(codesOf(bp)).toContain("PLAINTEXT_MULTI_TENANT");
    expect(codesOf({ ...bp, transport: "mtls", tlsMaterialDir: "/tmp/tls" })).not.toContain(
      "PLAINTEXT_MULTI_TENANT",
    );
  });

  it("refuses a fractional replica count and a non-positive quota", () => {
    const bp = clone(ordersPlatform!);
    (bp.nodes.find((n) => n.kind === "deployment")!.data as { replicas: number }).replicas = 1.5;
    const tenant = bp.nodes.find((n) => n.kind === "tenant")!;
    const quota = (tenant.data as TenantData).quota;
    tenant.data = { ...tenant.data, quota: { ...quota, maxInstances: -1 } };
    const codes = codesOf(bp);
    expect(codes).toContain("REPLICAS_FRACTIONAL");
    expect(codes).toContain("QUOTA_NOT_POSITIVE");
  });
});

describe("faults the designer used to ship silently", () => {
  it("resolves a Service's typed target against a DaemonSet, as its own edge rule already does", () => {
    const bp = clone(ordersPlatform!);
    const daemon = structuredClone(bp.nodes.find((n) => n.kind === "deployment")!);
    daemon.id = "w-daemon";
    daemon.kind = "daemonSet";
    daemon.data = { ...daemon.data, name: "collector" };
    bp.nodes.push(daemon);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    bp.edges = bp.edges.filter((e) => e.source !== service.id);
    service.data = { ...service.data, deploymentNames: ["collector"] };

    expect(codesOf(bp)).not.toContain("SERVICE_TARGET_MISSING");
  });

  it("reports a tenant-scoped resource declared twice, which silently overwrites at apply time", () => {
    const bp = clone(ordersPlatform!);
    const config = bp.nodes.find((n) => n.kind === "configEntry")!;
    const twin = structuredClone(config);
    twin.id = "c-twin";
    bp.nodes.push(twin);
    bp.edges = [
      ...bp.edges,
      ...bp.edges
        .filter((e) => e.source === config.id)
        .map((e) => ({ ...e, id: "e-twin", source: twin.id })),
    ];

    expect(codesOf(bp)).toContain("CONFIG_DUPLICATE");
  });

  it("warns that a second fafnir replica's key file is dropped, rather than dropping it silently", () => {
    const bp = clone(ordersPlatform!);
    const fafnir = bp.nodes.find((n) => n.kind === "fafnir")!;
    (fafnir.data as { keyFile?: string }).keyFile = "/keys/a.key";
    const second = structuredClone(fafnir);
    second.id = "r-fafnir-2";
    (second.data as { keyFile?: string }).keyFile = "/keys/different.key";
    bp.nodes.push(second);

    expect(codesOf(bp)).toContain("FAFNIR_KEYFILE_PER_ROLE");
  });
});
