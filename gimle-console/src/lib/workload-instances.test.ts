import { describe, expect, it } from "vitest";
import {
  WORKLOAD_INSTANCE_PAGE,
  daemonSetInstanceRows,
  deploymentInstanceRows,
  instanceWindow,
  statefulSetInstanceRows,
} from "./workload-instances";
import type { DaemonSet, Deployment, InstanceObservation, StatefulSet } from "@/types";

function observation(overrides: Partial<InstanceObservation> = {}): InstanceObservation {
  return {
    lifecycleState: "ACTIVE",
    alive: true,
    ready: true,
    requestRatePerSecond: 1.5,
    errorRatePerSecond: 0,
    queueDepth: 2,
    cpuMillicoresUsed: 300,
    memoryBytesUsed: 1024,
    workerId: "worker-1",
    ...overrides,
  };
}

const moduleId = { name: "greeter", version: "1.0.0" };

function deployment(replicaCount: number): Deployment {
  return {
    spec: {
      name: "web",
      moduleId,
      artifactPath: "/artifacts/web.jar",
      replicas: replicaCount,
      tenantId: "acme",
    },
    instances: Array.from({ length: replicaCount }, (_, i) => ({
      instanceIndex: i,
      nodeId: `node-${i}`,
      observation: observation(),
    })),
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
  };
}

describe("deploymentInstanceRows", () => {
  it("carries the workload's own identity onto every row the shared table needs", () => {
    const rows = deploymentInstanceRows(deployment(2));

    expect(rows).toHaveLength(2);
    expect(rows[0]).toEqual({
      deploymentName: "web",
      instanceIndex: 0,
      moduleId,
      artifactPath: "/artifacts/web.jar",
      tenantId: "acme",
      nodeId: "node-0",
      ...observation(),
    });
  });

  it("preserves each instance's own observed signals rather than collapsing them", () => {
    const d = deployment(2);
    d.instances[1].observation = observation({ lifecycleState: "FAILED", alive: false });

    const rows = deploymentInstanceRows(d);

    expect(rows[0].lifecycleState).toBe("ACTIVE");
    expect(rows[1].lifecycleState).toBe("FAILED");
    expect(rows[1].alive).toBe(false);
  });

  it("maps an unplaced workload to no rows at all, not to a placeholder row", () => {
    expect(deploymentInstanceRows(deployment(0))).toEqual([]);
  });
});

describe("daemonSetInstanceRows", () => {
  const daemonSet: DaemonSet = {
    spec: {
      name: "collector",
      moduleId,
      artifactPath: "/artifacts/collector.jar",
      placement: { requiredNodeLabels: [] },
      tenantId: null,
    },
    instances: [
      { nodeId: "node-a", observation: observation() },
      { nodeId: "node-b", observation: observation({ ready: false }) },
    ],
  };

  it("reports index 0 for every node, the index the log API addresses a DaemonSet instance by", () => {
    expect(daemonSetInstanceRows(daemonSet).map((r) => r.instanceIndex)).toEqual([0, 0]);
  });

  it("keeps each node distinguishable, since the index alone no longer identifies a row", () => {
    expect(daemonSetInstanceRows(daemonSet).map((r) => r.nodeId)).toEqual(["node-a", "node-b"]);
    const keys = daemonSetInstanceRows(daemonSet).map(
      (r) => `${r.deploymentName}#${r.instanceIndex}@${r.nodeId}`,
    );
    expect(new Set(keys).size).toBe(2);
  });

  it("carries an untenanted DaemonSet through as untenanted rather than inventing a tenant", () => {
    expect(daemonSetInstanceRows(daemonSet)[0].tenantId).toBeNull();
  });
});

describe("statefulSetInstanceRows", () => {
  const statefulSet: StatefulSet = {
    spec: {
      name: "ledger",
      moduleId,
      artifactPath: "/artifacts/ledger.jar",
      replicas: 3,
      tenantId: "acme",
    },
    instances: [
      { instanceIndex: 0, nodeId: "node-a", observation: observation() },
      { instanceIndex: 2, nodeId: "node-c", observation: observation() },
    ],
    unplacedCount: 1,
  };

  it("keeps each index's own sticky identity rather than renumbering by position", () => {
    expect(statefulSetInstanceRows(statefulSet).map((r) => r.instanceIndex)).toEqual([0, 2]);
    expect(statefulSetInstanceRows(statefulSet).map((r) => r.nodeId)).toEqual(["node-a", "node-c"]);
  });
});

describe("instanceWindow", () => {
  const rows = Array.from({ length: 120 }, (_, i) => i);

  it("caps a large workload at one page and reports that more remain", () => {
    const { visible, hasMore } = instanceWindow(rows, WORKLOAD_INSTANCE_PAGE);
    expect(visible).toHaveLength(WORKLOAD_INSTANCE_PAGE);
    expect(hasMore).toBe(true);
  });

  it("grows a page at a time and stops reporting more once everything is shown", () => {
    expect(instanceWindow(rows, WORKLOAD_INSTANCE_PAGE * 2).visible).toHaveLength(
      WORKLOAD_INSTANCE_PAGE * 2,
    );
    const all = instanceWindow(rows, 500);
    expect(all.visible).toHaveLength(120);
    expect(all.hasMore).toBe(false);
  });

  it("never reports more remaining for a workload that fits inside one page", () => {
    const { visible, hasMore } = instanceWindow([1, 2, 3], WORKLOAD_INSTANCE_PAGE);
    expect(visible).toEqual([1, 2, 3]);
    expect(hasMore).toBe(false);
  });

  it("handles an empty workload and a nonsensical count without throwing", () => {
    expect(instanceWindow([], WORKLOAD_INSTANCE_PAGE)).toEqual({ visible: [], hasMore: false });
    expect(instanceWindow(rows, -5)).toEqual({ visible: [], hasMore: true });
  });
});
