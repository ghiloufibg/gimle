import type { Permission, Role } from "@/types";
import type { RolesRepository } from "../roles";
import { requestJson, requestOk } from "./apiClient";

/** GET/PUT/DELETE /roles -- flat array response, no pagination. */
export class HttpRolesRepository implements RolesRepository {
  async fetchAll(): Promise<Role[]> {
    return requestJson<Role[]>("GET", "/roles");
  }
  async fetchOne(name: string): Promise<Role> {
    return requestJson<Role>("GET", `/roles/${encodeURIComponent(name)}`);
  }
  async save(name: string, permissions: Permission[]): Promise<void> {
    await requestOk("PUT", `/roles/${encodeURIComponent(name)}`, { permissions });
  }
  async remove(name: string): Promise<void> {
    await requestOk("DELETE", `/roles/${encodeURIComponent(name)}`);
  }
}
