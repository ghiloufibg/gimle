import type { Account } from "@/types";
import type { AccountsRepository } from "../accounts";
import { requestJson, requestOk } from "./apiClient";

/** GET/PUT/DELETE /accounts -- flat array response, no pagination. */
export class HttpAccountsRepository implements AccountsRepository {
  async fetchAll(): Promise<Account[]> {
    return requestJson<Account[]>("GET", "/accounts");
  }
  async fetchOne(username: string): Promise<Account> {
    return requestJson<Account>("GET", `/accounts/${encodeURIComponent(username)}`);
  }
  async savePassword(username: string, password: string, groups?: string[]): Promise<void> {
    const body: Record<string, unknown> = { password };
    if (groups !== undefined) body.groups = groups;
    await requestOk("PUT", `/accounts/${encodeURIComponent(username)}`, body);
  }
  async remove(username: string): Promise<void> {
    await requestOk("DELETE", `/accounts/${encodeURIComponent(username)}`);
  }
}
