import type { Principal } from "@/types";
import { delay } from "./util";

export interface AuthRepository {
  login(username: string, password: string): Promise<Principal>;
  logout(): Promise<void>;
  session(): Promise<Principal | null>;
}

const VALID_USERNAME = "admin";
const VALID_PASSWORD = "admin";

export class MockAuthRepository implements AuthRepository {
  private current: Principal | null = null;

  async login(username: string, password: string): Promise<Principal> {
    await delay(undefined);
    if (username !== VALID_USERNAME || password !== VALID_PASSWORD) {
      throw new Error("invalid username or password");
    }
    this.current = { username, groups: ["operators", "admins"] };
    return this.current;
  }

  async logout(): Promise<void> {
    await delay(undefined);
    this.current = null;
  }

  async session(): Promise<Principal | null> {
    return delay(this.current);
  }
}
