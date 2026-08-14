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
  async savePassword(username: string, password: string): Promise<void> {
    await requestOk("PUT", `/accounts/${encodeURIComponent(username)}`, { password });
  }
  async remove(username: string): Promise<void> {
    await requestOk("DELETE", `/accounts/${encodeURIComponent(username)}`);
  }
}
