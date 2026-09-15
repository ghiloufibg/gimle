import type { ControlPlaneStatus, SubsystemHealth, SubsystemStatus } from "@/types";
import type { ControlPlaneHealthRepository } from "@/repositories/controlPlaneHealth";
import { requestJson } from "./apiClient";

// Wire shape -- mirrors ApiServer.java's subsystemJson()/subsystemsJson(). lastRunAt/detail are
// omitted entirely rather than sent null (Optional#ifPresent on the Java side), so both are
// optional here and normalized below.
interface RawSubsystemHealth {
  status: SubsystemStatus;
  lastRunAt?: string;
  detail?: string;
}

interface RawHealthResponse {
  status: "UP" | "DOWN";
  reconcilerLeader?: boolean;
  subsystems?: Record<string, RawSubsystemHealth>;
}

function normalize(raw: RawSubsystemHealth | undefined): SubsystemHealth {
  return {
    status: raw?.status ?? "UNKNOWN",
    lastRunAt: raw?.lastRunAt ?? null,
    detail: raw?.detail ?? null,
  };
}

export class HttpControlPlaneHealthRepository implements ControlPlaneHealthRepository {
  async fetch(): Promise<ControlPlaneStatus> {
    // A 503 (store unreachable) throws ApiError here -- none of these subsystems can run without
    // the store, so there is no partial badge state worth salvaging; the screen shows the error.
    const raw = await requestJson<RawHealthResponse>("GET", "/health");
    const subsystems = raw.subsystems ?? {};
    return {
      reconcilerLeader: raw.reconcilerLeader ?? false,
      scheduler: normalize(subsystems.scheduler),
      quotaEnforcer: normalize(subsystems.quotaEnforcer),
      heartbeatWorker: normalize(subsystems.heartbeatWorker),
      artifactResolver: normalize(subsystems.artifactResolver),
    };
  }
}
