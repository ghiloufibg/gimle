import type { Principal } from "@/types";

export interface AuthRepository {
  login(username: string, password: string): Promise<Principal>;
  logout(): Promise<void>;
  session(): Promise<Principal>;
}

const delay = (ms = 220) => new Promise((resolve) => setTimeout(resolve, ms));

export class MockAuthRepository implements AuthRepository {
  private principal: Principal | null = null;

  async login(username: string, password: string): Promise<Principal> {
    await delay();
    if (!username || !password) throw new Error("invalid username or password");
    this.principal = { username, groups: ["operators", "vault-admins"] };
    return this.principal;
  }

  async logout(): Promise<void> {
    await delay(80);
    this.principal = null;
  }

  async session(): Promise<Principal> {
    await delay(80);
    if (!this.principal) throw new Error("not authenticated");
    return this.principal;
  }
}
