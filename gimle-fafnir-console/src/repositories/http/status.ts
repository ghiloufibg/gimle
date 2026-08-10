import type { StatusRepository } from "@/repositories/status";
import type { VaultStatus } from "@/types";

import { requestJson } from "./apiClient";

export class HttpStatusRepository implements StatusRepository {
  fetchStatus(): Promise<VaultStatus> {
    return requestJson<VaultStatus>("/status");
  }
}
