import type { RegistryStatus } from "@/types";
import type { StatusRepository } from "../types";
import { requestJson } from "./apiClient";

export class HttpStatusRepository implements StatusRepository {
  status(): Promise<RegistryStatus> {
    return requestJson<RegistryStatus>("/status");
  }
}
