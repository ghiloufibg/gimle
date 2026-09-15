import type { ControlPlaneStatus } from "@/types";
import { delay } from "./util";

/**
 * `GET /health`'s per-subsystem view -- what backs the Control plane screen's scheduler/quota
 * enforcer/heartbeat worker/artifact resolver badges. Distinct from a plain up/down ping: each
 * subsystem carries its own last-observed outcome, not one aggregate boolean.
 */
export interface ControlPlaneHealthRepository {
  fetch(): Promise<ControlPlaneStatus>;
}

const mockStatus: ControlPlaneStatus = {
  reconcilerLeader: true,
  scheduler: { status: "UP", lastRunAt: new Date().toISOString(), detail: null },
  quotaEnforcer: { status: "UP", lastRunAt: new Date().toISOString(), detail: null },
  heartbeatWorker: { status: "UP", lastRunAt: new Date().toISOString(), detail: null },
  artifactResolver: {
    status: "UP",
    lastRunAt: new Date().toISOString(),
    detail: "no artifact registry configured (local paths only)",
  },
};

export class MockControlPlaneHealthRepository implements ControlPlaneHealthRepository {
  async fetch(): Promise<ControlPlaneStatus> {
    return delay(mockStatus);
  }
}
