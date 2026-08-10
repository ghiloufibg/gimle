export interface Principal {
  username: string;
  groups: string[];
}

export interface VaultStatus {
  uptimeSeconds: number;
  activeKeyId: number;
  transportProtocol: "PLAINTEXT" | "TLS";
  tenants: string[];
}

export interface SecretMetadata {
  tenantId: string;
  key: string;
  latestVersion: number;
  deleted: boolean;
}

export interface SecretValue {
  tenantId: string;
  key: string;
  version: number;
  value: string; // already decoded to plain text by the repository layer
}
