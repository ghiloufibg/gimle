import type { Principal } from "@/types";
import type { AuthRepository } from "../types";
import { ApiError, requestJson, requestJsonWithBody, requestOk } from "./apiClient";

export class HttpAuthRepository implements AuthRepository {
  async session(): Promise<Principal | null> {
    try {
      return await requestJson<Principal>("/auth/session");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return null;
      throw error;
    }
  }

  async login(username: string, password: string): Promise<Principal> {
    try {
      return await requestJsonWithBody<Principal>("/auth/login", "POST", { username, password });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        throw new ApiError(401, "invalid username or password");
      }
      throw error;
    }
  }

  async logout(): Promise<void> {
    await requestOk("/auth/logout", "POST");
  }
}
