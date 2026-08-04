import type { Principal } from "@/types";
import type { AuthRepository } from "@/repositories/auth";
import { ApiError, requestJson, requestJsonWithBody, requestOk } from "./apiClient";

export class HttpAuthRepository implements AuthRepository {
  async login(username: string, password: string): Promise<Principal> {
    try {
      return await requestJsonWithBody<Principal>("POST", "/auth/login", { username, password });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        throw new Error("invalid username or password");
      }
      throw e;
    }
  }

  async logout(): Promise<void> {
    await requestOk("POST", "/auth/logout");
  }

  /** null, not a thrown ApiError, when there's no valid session -- the one call site that treats
   * "not logged in" as an expected result rather than a failure. */
  async session(): Promise<Principal | null> {
    try {
      return await requestJson<Principal>("GET", "/auth/session");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        return null;
      }
      throw e;
    }
  }
}
