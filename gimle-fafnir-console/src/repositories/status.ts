import type { VaultStatus } from "@/types";

export interface StatusRepository {
  fetchStatus(): Promise<VaultStatus>;
}

const delay = (ms = 220) => new Promise((resolve) => setTimeout(resolve, ms));

export class MockStatusRepository implements StatusRepository {
  async fetchStatus(): Promise<VaultStatus> {
    await delay();
    return {
      uptimeSeconds: 273_120,
      activeKeyId: 7,
      transportProtocol: "TLS",
      tenants: ["asgard", "midgard", "niflheim"],
    };
  }
}
