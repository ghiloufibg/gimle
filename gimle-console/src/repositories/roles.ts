import type { Permission, Role } from "@/types";
import { removeRole, roles, upsertRole } from "./rbacFixture";
import { delay } from "./util";

export interface RolesRepository {
  fetchAll(): Promise<Role[]>;
  fetchOne(name: string): Promise<Role>;
  save(name: string, permissions: Permission[]): Promise<void>;
  remove(name: string): Promise<void>;
}

export class MockRolesRepository implements RolesRepository {
  async fetchAll() {
    return delay(roles.map((r) => ({ ...r, permissions: [...r.permissions] })));
  }
  async fetchOne(name: string) {
    const r = roles.find((x) => x.name === name);
    if (!r) throw new Error(`Role not found: ${name}`);
    return delay({ ...r, permissions: [...r.permissions] });
  }
  async save(name: string, permissions: Permission[]) {
    upsertRole({ name, permissions });
    return delay(undefined);
  }
  async remove(name: string) {
    removeRole(name);
    return delay(undefined);
  }
}
