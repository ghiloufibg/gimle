import type { RoleBinding } from "@/types";
import { removeRoleBinding, roleBindings, upsertRoleBinding } from "./rbacFixture";
import { delay } from "./util";

export interface RoleBindingsRepository {
  fetchAll(): Promise<RoleBinding[]>;
  fetchOne(id: string): Promise<RoleBinding>;
  save(id: string, body: { subject: string; roleName: string }): Promise<void>;
  remove(id: string): Promise<void>;
}

export class MockRoleBindingsRepository implements RoleBindingsRepository {
  async fetchAll() {
    return delay(roleBindings.map((b) => ({ ...b })));
  }
  async fetchOne(id: string) {
    const b = roleBindings.find((x) => x.id === id);
    if (!b) throw new Error(`Role binding not found: ${id}`);
    return delay({ ...b });
  }
  async save(id: string, body: { subject: string; roleName: string }) {
    upsertRoleBinding({ id, ...body });
    return delay(undefined);
  }
  async remove(id: string) {
    removeRoleBinding(id);
    return delay(undefined);
  }
}
