import { describe, expect, it } from "vitest";
import { isDeploymentHealthy, isInstanceHealthy } from "./deployment-health";
import type { Deployment, DeploymentInstance, InstanceObservation } from "@/types";

function observation(overrides: Partial<InstanceObservation> = {}): InstanceObservation {
  return {
    lifecycleState: "ACTIVE",
    alive: true,
    ready: true,
    requestRatePerSecond: 0,
    errorRatePerSecond: 0,
    queueDepth: 0,
    cpuMillicoresUsed: 0,
    memoryBytesUsed: 0,
    workerId: null,
    ...overrides,
  };
}

function instance(overrides: Partial<InstanceObservation> = {}): DeploymentInstance {
  return {
    instanceIndex: 0,
    nodeId: "node-a",
    observation: observation(overrides),
  };
}

function deployment(overrides: Partial<Deployment> = {}): Deployment {
  return {
    spec: {
      name: "greeter",
      moduleId: { name: "com.gimle.example.greeter", version: "1.0.0" },
      artifactPath: "greeter.jar",
      replicas: 1,
      tenantId: null,
    },
    instances: [instance()],
    unplacedCount: 0,
    quotaViolating: false,
    limitRangeViolating: false,
    ...overrides,
  };
}

describe("isInstanceHealthy", () => {
  it("is healthy when ACTIVE, alive, and ready", () => {
    expect(isInstanceHealthy(instance())).toBe(true);
  });

  it("is not healthy when FAILED even if alive/ready flags are stale-true", () => {
    expect(isInstanceHealthy(instance({ lifecycleState: "FAILED" }))).toBe(false);
  });

  it("is not healthy when not alive", () => {
    expect(isInstanceHealthy(instance({ alive: false }))).toBe(false);
  });

  it("is not healthy when alive but not ready", () => {
    expect(isInstanceHealthy(instance({ ready: false }))).toBe(false);
  });
});

describe("isDeploymentHealthy", () => {
  it("is healthy when fully placed and every instance is ACTIVE/alive/ready", () => {
    expect(isDeploymentHealthy(deployment())).toBe(true);
  });

  // The actual OBS-7 regression: replicas placed == replicas desired, but the one placed
  // instance is FAILED -- placement count alone previously read this as healthy.
  it("is not healthy when the only instance is FAILED, even with full placement counts", () => {
    const d = deployment({ instances: [instance({ lifecycleState: "FAILED", alive: false })] });
    expect(isDeploymentHealthy(d)).toBe(false);
  });

  it("is not healthy when an instance is alive but not yet ready", () => {
    const d = deployment({ instances: [instance({ ready: false })] });
    expect(isDeploymentHealthy(d)).toBe(false);
  });

  it("is not healthy when there are unplaced replicas", () => {
    const d = deployment({ unplacedCount: 1, spec: { ...deployment().spec, replicas: 2 } });
    expect(isDeploymentHealthy(d)).toBe(false);
  });

  it("is not healthy when quota violating", () => {
    expect(isDeploymentHealthy(deployment({ quotaViolating: true }))).toBe(false);
  });

  it("is not healthy when limit-range violating", () => {
    expect(isDeploymentHealthy(deployment({ limitRangeViolating: true }))).toBe(false);
  });

  it("is not healthy when fewer instances are placed than desired replicas", () => {
    const d = deployment({ instances: [], spec: { ...deployment().spec, replicas: 1 } });
    expect(isDeploymentHealthy(d)).toBe(false);
  });

  it("is healthy with zero desired replicas and none placed", () => {
    const d = deployment({ instances: [], spec: { ...deployment().spec, replicas: 0 } });
    expect(isDeploymentHealthy(d)).toBe(true);
  });
});
