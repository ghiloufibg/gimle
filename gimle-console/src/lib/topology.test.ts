import { describe, expect, it } from "vitest";
import type { Deployment, DeploymentInstance } from "@/types";
import { replicaBadgeSlots } from "./topology";

const OBSERVATION: DeploymentInstance["observation"] = {
  lifecycleState: "ACTIVE",
  alive: true,
  ready: true,
  requestRatePerSecond: 0,
  errorRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: null,
};

function instance(instanceIndex: number, nodeId: string): DeploymentInstance {
  return { instanceIndex, nodeId, observation: OBSERVATION };
}

function deployment(instances: DeploymentInstance[], replicas = instances.length): Deployment {
  return {
    spec: {
      name: "checkout-service",
      moduleId: { name: "checkout-service", version: "1.0.0" },
      artifactPath: "",
      replicas,
      tenantId: null,
    },
    instances,
    unplacedCount: Math.max(0, replicas - instances.length),
    quotaViolating: false,
    limitRangeViolating: false,
  };
}

describe("replicaBadgeSlots", () => {
  // Regression: the Topology screen used to label each badge by its position in the response
  // array (`instance ${i}`) instead of the instance's own instanceIndex -- correct only when the
  // control plane happens to return instances already in ascending-index order. This reproduces
  // the exact reported case: three instances at indices 0/1/2, returned out of order (2, 1, 0).
  it("orders slots by each instance's own instanceIndex, not the array's arrival order", () => {
    const d = deployment([
      instance(2, "agent-edge"),
      instance(1, "agent-1"),
      instance(0, "agent-2"),
    ]);

    const slots = replicaBadgeSlots(d);

    expect(slots.map((s) => s?.instanceIndex)).toEqual([0, 1, 2]);
    expect(slots[0]?.nodeId).toBe("agent-2");
    expect(slots[1]?.nodeId).toBe("agent-1");
    expect(slots[2]?.nodeId).toBe("agent-edge");
  });

  it("already-ordered input stays in the same order", () => {
    const d = deployment([
      instance(0, "agent-2"),
      instance(1, "agent-1"),
      instance(2, "agent-edge"),
    ]);

    const slots = replicaBadgeSlots(d);

    expect(slots.map((s) => s?.nodeId)).toEqual(["agent-2", "agent-1", "agent-edge"]);
  });

  it("pads trailing null slots for replicas the manifest asks for but nothing has placed yet", () => {
    const d = deployment([instance(0, "agent-1")], 3);

    const slots = replicaBadgeSlots(d);

    expect(slots).toHaveLength(3);
    expect(slots[0]?.instanceIndex).toBe(0);
    expect(slots[1]).toBeNull();
    expect(slots[2]).toBeNull();
  });

  it("never truncates below the placed count even if replicas somehow reports fewer", () => {
    const d = deployment([instance(0, "agent-1"), instance(1, "agent-2")], 1);

    const slots = replicaBadgeSlots(d);

    expect(slots).toHaveLength(2);
  });
});
