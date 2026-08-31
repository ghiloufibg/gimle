import type { Account } from "@/types";
import { accounts, removeAccount, upsertAccount } from "./rbacFixture";
import { delay } from "./util";

export interface AccountsRepository {
  fetchAll(): Promise<Account[]>;
  fetchOne(username: string): Promise<Account>;
  /**
   * Create-or-reset: PUT /accounts/{username} with {password, groups?}. Omitting `groups`
   * preserves the account's existing group membership -- a plain password reset must never
   * silently strip a `group:` RoleBinding's eligibility.
   */
  savePassword(username: string, password: string, groups?: string[]): Promise<void>;
  remove(username: string): Promise<void>;
}

export class MockAccountsRepository implements AccountsRepository {
  async fetchAll() {
    return delay(accounts.map((a) => ({ ...a })));
  }
  async fetchOne(username: string) {
    const a = accounts.find((x) => x.username === username);
    if (!a) throw new Error(`Account not found: ${username}`);
    return delay({ ...a });
  }
  async savePassword(username: string, _password: string, groups?: string[]) {
    void _password;
    upsertAccount(username, groups);
    return delay(undefined);
  }
  async remove(username: string) {
    removeAccount(username);
    return delay(undefined);
  }
}
