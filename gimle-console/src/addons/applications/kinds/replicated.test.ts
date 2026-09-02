import { describe, expect, it } from "vitest";

import type {
  DaemonSet,
  Deployment,
  InstanceObservation,
  LifecycleState,
  Service,
  StatefulSet,
} from "@/types";
import {
  fromDaemonSet,
  fromDeployment,
  fromStatefulSet,
} from "@/addons/applications/kinds/replicated";

function observation(
  lifecycleState: LifecycleState,
  alive: boolean,
  ready: boolean,
): InstanceObservation {
  return {
    lifecycleState,
    alive,
    ready,
    requestRatePerSecond: 0,
    errorRatePerSecond: 0,
    queueDepth: 0,
    cpuMillicoresUsed: 0,
    memoryBytesUsed: 0,
    workerId: null,
  };
}

const ACTIVE = observation("ACTIVE", true, true);

function deployment(overrides: Partial<Deployment> = {}): Deployment {
  return {
    spec: {
      name: "orders",
      moduleId: { name: "orders", version: "1.0.0" },
      artifactPath: "",
      replicas: 1,
      tenantId: null,
    },
    instances: [{ instanceIndex: 0, nodeId: "node-a", observation: ACTIVE }],
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
    ...overrides,
  };
}

describe("deployment health", () => {
  it("is healthy when every desired replica is placed, alive and ready", () => {
    const app = fromDeployment(deployment(), []);
    expect(app.health).toBe("Healthy");
    expect(app.sync).toBe("Synced");
    expect(app.conditions).toEqual([]);
  });

  it("reads an ACTIVE but not ready instance as progressing, not degraded", () => {
    const app = fromDeployment(
      deployment({
        instances: [
          { instanceIndex: 0, nodeId: "node-a", observation: observation("ACTIVE", true, false) },
        ],
      }),
      [],
    );
    expect(app.health).toBe("Progressing");
    expect(app.conditions.map((c) => c.type)).toEqual(["InstanceNotReady"]);
  });

  it("reads an ACTIVE but not alive instance as degraded", () => {
    const app = fromDeployment(
      deployment({
        instances: [
          { instanceIndex: 0, nodeId: "node-a", observation: observation("ACTIVE", false, true) },
        ],
      }),
      [],
    );
    expect(app.health).toBe("Degraded");
    expect(app.conditions[0].message).toContain("ACTIVE but not alive");
  });

  it.each(["INSTALLED", "RESOLVED", "STARTING", "STOPPING"] as const)(
    "reads a %s instance as progressing",
    (state) => {
      const app = fromDeployment(
        deployment({
          instances: [
            { instanceIndex: 0, nodeId: "node-a", observation: observation(state, false, false) },
          ],
        }),
        [],
      );
      expect(app.health).toBe("Progressing");
    },
  );

  it.each(["FAILED", "UNINSTALLED"] as const)("reads a %s instance as degraded", (state) => {
    const app = fromDeployment(
      deployment({
        instances: [
          { instanceIndex: 0, nodeId: "node-a", observation: observation(state, false, false) },
        ],
      }),
      [],
    );
    expect(app.health).toBe("Degraded");
  });

  it("is unknown, never healthy, when nothing is placed and nothing is wanted", () => {
    const app = fromDeployment(
      deployment({ spec: { ...deployment().spec, replicas: 0 }, instances: [] }),
      [],
    );
    expect(app.health).toBe("Unknown");
    expect(app.sync).toBe("Synced");
  });

  it("reports a quota violation as degraded and out of sync", () => {
    const app = fromDeployment(deployment({ quotaViolating: true }), []);
    expect(app.health).toBe("Degraded");
    expect(app.sync).toBe("OutOfSync");
    expect(app.conditions.map((c) => c.type)).toContain("QuotaViolation");
  });

  it("carries the server's own reason on a LimitRange violation", () => {
    const app = fromDeployment(
      deployment({
        limitRangeViolating: true,
        limitRangeViolationReason: "memory request 64Mi below the minimum 128Mi",
      }),
      [],
    );
    expect(app.conditions[0].message).toBe("memory request 64Mi below the minimum 128Mi");
  });

  it("counts unplaced replicas in the condition text", () => {
    const app = fromDeployment(
      deployment({ spec: { ...deployment().spec, replicas: 3 }, unplacedCount: 2, instances: [] }),
      [],
    );
    expect(app.health).toBe("Degraded");
    expect(app.conditions.map((c) => c.message)).toContain(
      "2 replicas have no feasible placement on any node",
    );
  });
});

describe("deployment sync", () => {
  it("stays synced through a crashed replica -- the desired state was reached", () => {
    const app = fromDeployment(
      deployment({
        instances: [
          { instanceIndex: 0, nodeId: "node-a", observation: observation("FAILED", false, false) },
        ],
      }),
      [],
    );
    expect(app.sync).toBe("Synced");
    expect(app.health).toBe("Degraded");
  });

  it("is out of sync while scaling up, with every placed replica healthy", () => {
    const app = fromDeployment(deployment({ spec: { ...deployment().spec, replicas: 2 } }), []);
    expect(app.sync).toBe("OutOfSync");
    expect(app.health).toBe("Progressing");
    expect(app.conditions.map((c) => c.message)).toContain("1 of 2 desired replicas placed");
  });

  it("is out of sync while a scale-down surplus is still draining", () => {
    const app = fromDeployment(
      deployment({
        spec: { ...deployment().spec, replicas: 1 },
        instances: [
          { instanceIndex: 0, nodeId: "node-a", observation: ACTIVE },
          { instanceIndex: 1, nodeId: "node-b", observation: ACTIVE },
        ],
      }),
      [],
    );
    expect(app.sync).toBe("OutOfSync");
    expect(app.conditions.map((c) => c.type)).toContain("ScalingDown");
  });
});

describe("services fronting an application", () => {
  const service = (over: Partial<Service> = {}): Service => ({
    name: "orders-web",
    deploymentNames: ["orders"],
    port: 8080,
    ...over,
  });

  it("attaches a Service naming this workload in the same tenant", () => {
    const app = fromDeployment(deployment(), [service()]);
    expect(app.services.map((s) => s.name)).toEqual(["orders-web"]);
  });

  it("never attaches another tenant's same-named Service", () => {
    const app = fromDeployment(deployment(), [service({ tenantId: "acme" })]);
    expect(app.services).toEqual([]);
  });

  it("never attaches a Service that does not name this workload", () => {
    const app = fromDeployment(deployment(), [service({ deploymentNames: ["billing"] })]);
    expect(app.services).toEqual([]);
  });
});

describe("statefulset and daemonset", () => {
  const statefulSet: StatefulSet = {
    spec: {
      name: "ledger",
      moduleId: { name: "ledger", version: "2.0.0" },
      artifactPath: "",
      replicas: 2,
      tenantId: "acme",
    },
    instances: [
      { instanceIndex: 0, nodeId: "node-a", observation: ACTIVE },
      { instanceIndex: 1, nodeId: "node-b", observation: ACTIVE },
    ],
    unplacedCount: 0,
  };

  const daemonSet: DaemonSet = {
    spec: {
      name: "log-shipper",
      moduleId: { name: "log-shipper", version: "0.4.0" },
      artifactPath: "",
      placement: { requiredNodeLabels: [] },
      tenantId: null,
    },
    instances: [{ nodeId: "node-a", observation: ACTIVE }],
  };

  it("reads a fully placed statefulset as healthy and synced", () => {
    const app = fromStatefulSet(statefulSet, []);
    expect([app.health, app.sync]).toEqual(["Healthy", "Synced"]);
    expect(app.instances.map((i) => i.label)).toEqual(["#0", "#1"]);
  });

  it("identifies a daemonset instance by its node, at instance index 0", () => {
    const app = fromDaemonSet(daemonSet, []);
    expect(app.instances[0].label).toBe("node-a");
    expect(app.instances[0].instanceIndex).toBe(0);
    expect(app.health).toBe("Healthy");
  });

  it("reads a daemonset no node runs as unknown, naming the labels it requires", () => {
    const app = fromDaemonSet(
      {
        ...daemonSet,
        spec: { ...daemonSet.spec, placement: { requiredNodeLabels: ["disk=ssd"] } },
        instances: [],
      },
      [],
    );
    expect(app.health).toBe("Unknown");
    expect(app.conditions[0].message).toContain("disk=ssd");
  });

  it("never calls a daemonset out of sync for having no replica count", () => {
    const app = fromDaemonSet({ ...daemonSet, instances: [] }, []);
    expect(app.sync).toBe("Synced");
  });
});
