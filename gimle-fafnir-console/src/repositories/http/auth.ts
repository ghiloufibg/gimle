import type { AuthRepository } from "@/repositories/auth";
import type { Principal } from "@/types";

import { requestJson, requestJsonWithBody, requestOk } from "./apiClient";

export class HttpAuthRepository implements AuthRepository {
  login(username: string, password: string): Promise<Principal> {
    return requestJsonWithBody<Principal>("/auth/login", "POST", { username, password });
  }

  logout(): Promise<void> {
    return requestOk("/auth/logout", { method: "POST" });
  }

  session(): Promise<Principal> {
    return requestJson<Principal>("/auth/session");
  }
}
