import type { RoleBinding } from "@/types";
import type { RoleBindingsRepository } from "../roleBindings";
import { requestJson, requestOk } from "./apiClient";

/** GET/PUT/DELETE /rolebindings -- flat array response, no pagination. */
export class HttpRoleBindingsRepository implements RoleBindingsRepository {
  async fetchAll(): Promise<RoleBinding[]> {
    return requestJson<RoleBinding[]>("GET", "/rolebindings");
  }
  async fetchOne(id: string): Promise<RoleBinding> {
    return requestJson<RoleBinding>("GET", `/rolebindings/${encodeURIComponent(id)}`);
  }
  async save(id: string, body: { subject: string; roleName: string }): Promise<void> {
    await requestOk("PUT", `/rolebindings/${encodeURIComponent(id)}`, body);
  }
  async remove(id: string): Promise<void> {
    await requestOk("DELETE", `/rolebindings/${encodeURIComponent(id)}`);
  }
}
