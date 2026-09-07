import { describe, expect, it } from "vitest";

import type { Blueprint, StoreData, TenantData } from "./blueprint";
import { createNode } from "./blueprint";
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

  it("flags a resource naming a tenant id that matches no declared tenant, as a blocking error", () => {
    const bp = clone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    bp.edges = bp.edges.filter((e) => e.source !== deployment.id);
    (deployment.data as { tenantId?: string }).tenantId = "no-such-tenant";
    const problems = validate(bp).filter((p) => p.code === "TENANT_UNKNOWN");
    expect(problems).toHaveLength(1);
    expect(problems[0].severity).toBe("error");
  });

  it("only warns, and does not block Run, when a resource simply has no tenant set", () => {
    // The control plane admits a blank tenantId unconditionally and runs it in the implicit
    // default tenant -- this is a real, supported configuration, not a misconfiguration the way
    // naming a tenant that doesn't exist is.
    const bp = clone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    bp.edges = bp.edges.filter((e) => e.source !== deployment.id);
    (deployment.data as { tenantId?: string }).tenantId = "";
    const problems = validate(bp).filter((p) => p.code === "TENANT_UNKNOWN");
    expect(problems).toHaveLength(1);
    expect(problems[0].severity).toBe("warning");
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
    lr.data = {
      ...lr.data,
      minRequest: { memory: "", cpu: "" },
      maxRequest: { memory: "", cpu: "" },
    };
    const codes = codesOf(bp);
    expect(codes).not.toContain("LIMITRANGE_VIOLATION");
    expect(codes).toContain("LIMITRANGE_NO_BOUNDS");
  });

  it("still faults a request outside a partially-filled limit range", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = {
      ...lr.data,
      minRequest: { memory: "8Gi", cpu: "" },
      maxRequest: { memory: "", cpu: "" },
    };
    expect(codesOf(bp)).toContain("LIMITRANGE_VIOLATION");
  });

  it("faults a workload's limit outside the limit range's own limit bound", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    // Every deployment in this tenant carries limit.memory 256Mi -- a max limit below that faults
    // the limit itself, distinct from the (still passing) request check above.
    lr.data = { ...lr.data, maxLimit: { memory: "1Mi", cpu: "10m" } };
    const problems = validate(bp).filter((p) => p.code === "LIMITRANGE_VIOLATION");
    expect(problems.some((p) => p.message.startsWith("Limit is outside"))).toBe(true);
  });

  it("does not fault a limit range whose limit bound is still empty", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = { ...lr.data, minLimit: { memory: "", cpu: "" }, maxLimit: { memory: "", cpu: "" } };
    const problems = validate(bp).filter((p) => p.code === "LIMITRANGE_VIOLATION");
    expect(problems.every((p) => !p.message.startsWith("Limit is outside"))).toBe(true);
  });

  it("flags a half-filled limit bound the same way a half-filled request bound is flagged", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = { ...lr.data, minLimit: { memory: "8Gi", cpu: "" } };
    expect(codesOf(bp)).toContain("LIMITRANGE_HALF_FILLED");
  });

  it("flags an inverted limit bound the same way an inverted request bound is flagged", () => {
    const bp = clone(ordersPlatform!);
    const lr = bp.nodes.find((n) => n.kind === "limitRange")!;
    lr.data = {
      ...lr.data,
      minLimit: { memory: "1Gi", cpu: "10m" },
      maxLimit: { memory: "512Mi", cpu: "1000m" },
    };
    expect(codesOf(bp)).toContain("LIMITRANGE_INVERTED");
  });

  it("refuses a jar-sourced workload with no registry to push it to", () => {
    const bp = clone(ordersPlatform!);
    bp.nodes = bp.nodes.filter((n) => n.kind !== "andvari");
    const workload = bp.nodes.find((n) => n.kind === "deployment")!;
    workload.data = { ...workload.data, artifact: { source: "jar", path: "/tmp/x.jar" } };
    expect(codesOf(bp)).toContain("NO_ANDVARI_FOR_JAR");
  });

  it("flags a jar-sourced workload with a blank artifact path as a blocking error", () => {
    const bp = clone(ordersPlatform!);
    const workload = bp.nodes.find((n) => n.kind === "deployment")!;
    workload.data = { ...workload.data, artifact: { source: "jar", path: "" } };
    const problems = validate(bp).filter((p) => p.code === "JAR_PATH_BLANK");
    expect(problems).toHaveLength(1);
    expect(problems[0].severity).toBe("error");
    // A blank path is a different, worse problem than a merely-relative one -- both must not fire
    // for the same field value.
    expect(codesOf(bp)).not.toContain("JAR_PATH_RELATIVE");
  });

  it("still flags a non-blank, non-absolute jar path as a warning", () => {
    const bp = clone(ordersPlatform!);
    const workload = bp.nodes.find((n) => n.kind === "deployment")!;
    workload.data = { ...workload.data, artifact: { source: "jar", path: "relative/path.jar" } };
    const problems = validate(bp).filter((p) => p.code === "JAR_PATH_RELATIVE");
    expect(problems).toHaveLength(1);
    expect(problems[0].severity).toBe("warning");
    expect(codesOf(bp)).not.toContain("JAR_PATH_BLANK");
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

  it("warns when two Services in the same tenant front the same deployment, mirroring the control plane's own ServiceAdvisories#overlapWarnings", () => {
    const bp = clone(ordersPlatform!);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    const twin = structuredClone(service);
    twin.id = "s-twin";
    twin.data = { ...twin.data, name: "twin-service" };
    bp.nodes.push(twin);
    bp.edges = [
      ...bp.edges,
      ...bp.edges
        .filter((e) => e.source === service.id)
        .map((e) => ({ ...e, id: "e-twin", source: twin.id })),
    ];

    const overlaps = validate(bp).filter((p) => p.code === "SERVICE_OVERLAP");
    expect(overlaps).toHaveLength(2); // one attributed to each service node
    expect(overlaps.map((p) => p.nodeId).sort()).toEqual([service.id, twin.id].sort());
  });

  it("does not warn about two Services in different tenants fronting a same-named deployment", () => {
    const bp = clone(ordersPlatform!);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    const otherTenant = structuredClone(bp.nodes.find((n) => n.kind === "tenant")!);
    otherTenant.id = "t-second";
    otherTenant.data = { ...otherTenant.data, id: "second-tenant" };
    const twin = structuredClone(service);
    twin.id = "s-twin";
    twin.data = { ...twin.data, name: "twin-service" };
    bp.nodes.push(otherTenant, twin);
    bp.edges = [
      ...bp.edges,
      { id: "e-twin-belongs", kind: "belongsTo", source: twin.id, target: otherTenant.id },
      ...bp.edges
        .filter((e) => e.source === service.id && e.kind === "fronts")
        .map((e) => ({ ...e, id: "e-twin-fronts", source: twin.id })),
    ];

    expect(codesOf(bp)).not.toContain("SERVICE_OVERLAP");
  });

  it("does not fault a Service's own blank targetPort, which the platform defaults to port", () => {
    const bp = clone(ordersPlatform!);
    const service = bp.nodes.find((n) => n.kind === "service")!;
    service.data = { ...service.data, targetPort: undefined };

    expect(codesOf(bp)).not.toContain("SERVICE_PORT_RANGE");
  });

  it("refuses a negative autoscale minReplicas, mirroring AutoscalePolicy's own compact constructor", () => {
    const bp = clone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    deployment.data = {
      ...deployment.data,
      autoscale: { minReplicas: -5, maxReplicas: 3, targetCpuUtilizationPercent: 70 },
    };

    expect(codesOf(bp)).toContain("AUTOSCALE_RANGE");
  });

  it("refuses a negative disruption maxUnavailable or maxSurge, mirroring DisruptionBudget's own compact constructor", () => {
    const bp = clone(ordersPlatform!);
    const deployment = bp.nodes.find((n) => n.kind === "deployment")!;
    deployment.data = {
      ...deployment.data,
      disruption: { maxUnavailable: -3, maxSurge: 1 },
    };
    expect(codesOf(bp)).toContain("DISRUPTION_RANGE");

    deployment.data = {
      ...deployment.data,
      disruption: { maxUnavailable: 1, maxSurge: -1 },
    };
    expect(codesOf(bp)).toContain("DISRUPTION_RANGE");
  });

  it("flags an allowed caller tenant that doesn't exist, mirroring the check for a callee tenant", () => {
    const bp = clone(ordersPlatform!);
    const policy = bp.nodes.find((n) => n.kind === "networkPolicy")!;
    policy.data = { ...policy.data, allowedCallerTenantIds: ["no-such-tenant"] };
    expect(codesOf(bp)).toContain("POLICY_ALLOWED_TENANT_UNKNOWN");
  });

  it("flags an allowed callee tenant that doesn't exist", () => {
    const bp = clone(ordersPlatform!);
    const policy = bp.nodes.find((n) => n.kind === "networkPolicy")!;
    policy.data = { ...policy.data, allowedCalleeTenantIds: ["no-such-tenant"] };
    expect(codesOf(bp)).toContain("POLICY_ALLOWED_CALLEE_UNKNOWN");
  });

  it("flags anti-affinity set on a DaemonSet, which the Inspector must still let a user clear", () => {
    // A DaemonSet's Anti-affinity checkbox used to be hidden entirely in the Inspector, so a
    // `true` value set some other way (an import, an old manifest) had no on-screen way back --
    // this is the finding that checkbox now surfaces so the user has a way to fix it.
    const bp = clone(ordersPlatform!);
    const daemonSet = createNode("daemonSet", { x: 0, y: 0 });
    (daemonSet.data as { placement?: { antiAffinity?: boolean } }).placement = {
      antiAffinity: true,
    };
    bp.nodes.push(daemonSet);
    expect(codesOf(bp)).toContain("DAEMONSET_ANTI_AFFINITY");
  });

  it("does not fault a callee tenant that is genuinely declared on the canvas", () => {
    const bp = clone(ordersPlatform!);
    const policy = bp.nodes.find((n) => n.kind === "networkPolicy")!;
    policy.data = { ...policy.data, allowedCalleeTenantIds: ["orders-platform"] };
    expect(codesOf(bp)).not.toContain("POLICY_ALLOWED_CALLEE_UNKNOWN");
  });

  it("names both claimants by node id when two same-kind nodes collide on one port", () => {
    const bp = clone(ordersPlatform!);
    const first = createNode("andvari", { x: 0, y: 0 });
    const second = createNode("andvari", { x: 0, y: 100 });
    (first.data as { machine: string }).machine = "local";
    (second.data as { machine: string }).machine = "local";
    bp.nodes.push(first, second);

    const message = validate(bp).find((p) => p.code === "PORT_CONFLICT")!.message;

    expect(message).toContain(`${first.id}'s andvari port`);
    expect(message).toContain(`${second.id}'s andvari port`);
  });
});
