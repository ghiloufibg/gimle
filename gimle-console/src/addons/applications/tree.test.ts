import { describe, expect, it } from "vitest";

import { fromCronJob, fromJob } from "@/addons/applications/kinds/jobs";
import { fromCustomResource } from "@/addons/applications/kinds/custom";
import { fromDeployment } from "@/addons/applications/kinds/replicated";
import { APPLICATION_NODE_ID, layoutTree } from "@/addons/applications/tree";
import type { CronJob, Deployment, InstanceObservation, Job, Service } from "@/types";

const OBSERVATION: InstanceObservation = {
  lifecycleState: "ACTIVE",
  alive: true,
  ready: true,
  requestRatePerSecond: 0,
  errorRatePerSecond: 0,
  queueDepth: 0,
  cpuMillicoresUsed: 0,
  memoryBytesUsed: 0,
  workerId: "worker-4471",
};

function deployment(nodeIds: string[]): Deployment {
  return {
    spec: {
      name: "orders",
      moduleId: { name: "orders", version: "1.0.0" },
      artifactPath: "",
      replicas: nodeIds.length,
      tenantId: null,
    },
    instances: nodeIds.map((nodeId, i) => ({
      instanceIndex: i,
      nodeId,
      observation: OBSERVATION,
    })),
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
  };
}

const service: Service = { name: "orders-web", deploymentNames: ["orders"], port: 8080 };

describe("tree layout", () => {
  it("lays every card out on its own lane within a tier", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a", "node-b"]), [service]));
    const seen = new Set<string>();
    for (const node of layout.nodes) {
      const cell = `${node.tier}:${node.row}`;
      expect(seen.has(cell)).toBe(false);
      seen.add(cell);
    }
  });

  it("centres a parent over the children it fans out to", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a", "node-b"]), []));
    const rowOf = (id: string) => layout.nodes.find((n) => n.id === id)!.row;
    expect(rowOf("revision")).toBe((rowOf("instance:#0") + rowOf("instance:#1")) / 2);
  });

  it("gives two replicas on one machine a single node card with two edges into it", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a", "node-a"]), []));
    const machines = layout.nodes.filter((n) => n.kind === "node");
    expect(machines.map((m) => m.title)).toEqual(["node-a"]);
    expect(layout.edges.filter((e) => e.to === "node:node-a")).toHaveLength(2);
  });

  it("never lets two machine cards land on the same lane", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a", "node-b", "node-a"]), []));
    const rows = layout.nodes.filter((n) => n.kind === "node").map((n) => n.row);
    expect(new Set(rows).size).toBe(rows.length);
  });

  it("connects every edge between cards that exist", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a", "node-b"]), [service]));
    const ids = new Set(layout.nodes.map((n) => n.id));
    for (const edge of layout.edges) {
      expect(ids.has(edge.from)).toBe(true);
      expect(ids.has(edge.to)).toBe(true);
    }
  });

  it("lays out an application with nothing placed, rather than collapsing", () => {
    const empty = deployment([]);
    const layout = layoutTree(fromDeployment({ ...empty, unplacedCount: 0 }, []));
    expect(layout.nodes.map((n) => n.id)).toContain(APPLICATION_NODE_ID);
    expect(layout.rows).toBeGreaterThan(0);
    expect(Number.isNaN(layout.rows)).toBe(false);
  });

  it("hangs a Service off the application, never off the revision", () => {
    const layout = layoutTree(fromDeployment(deployment(["node-a"]), [service]));
    const edge = layout.edges.find((e) => e.to === "service:orders-web");
    expect(edge?.from).toBe(APPLICATION_NODE_ID);
  });
});

describe("tree layout per kind", () => {
  const job = (name: string): Job => ({
    spec: {
      name,
      moduleId: { name: "reindexer", version: "1.2.0" },
      artifactPath: "",
      backoffLimit: 3,
      tenantId: null,
    },
    phase: "RUNNING",
    currentRun: { attempt: 2, nodeId: "node-c", observation: OBSERVATION },
  });

  it("lays a job out as module, attempt and node", () => {
    const layout = layoutTree(fromJob(job("reindex"), []));
    expect(layout.nodes.map((n) => n.eyebrow).sort()).toEqual([
      "application",
      "instance",
      "module",
      "node",
    ]);
    expect(layout.tiers).toBe(4);
  });

  it("hangs a cronjob's generated jobs and their attempts beneath it", () => {
    const cronJob: CronJob = {
      spec: {
        name: "nightly-report",
        schedule: "0 2 * * *",
        jobTemplate: {
          moduleId: { name: "reporter", version: "1.0.0" },
          artifactPath: "",
          backoffLimit: 1,
        },
        concurrencyPolicy: "FORBID",
        tenantId: null,
      },
      lastScheduleTime: "2026-09-02T02:00:00Z",
    };
    const generated = { ...job("nightly-report-1756778400"), phase: "SUCCEEDED" as const };
    const layout = layoutTree(fromCronJob(cronJob, [generated], []));
    expect(layout.edges).toContainEqual({
      from: APPLICATION_NODE_ID,
      to: "job:nightly-report-1756778400",
      health: "Healthy",
    });
    expect(layout.nodes.find((n) => n.kind === "node")?.title).toBe("node-c");
  });

  it("stops a custom resource's tree at its status card", () => {
    const layout = layoutTree(
      fromCustomResource(
        {
          kind: "custom.Greeting",
          name: "hello-world",
          generation: 4,
          spec: {},
          status: { observedGeneration: 3 },
        },
        undefined,
      ),
    );
    expect(layout.nodes.map((n) => n.kind).sort()).toEqual(["application", "status"]);
    expect(layout.tiers).toBe(2);
  });
});
