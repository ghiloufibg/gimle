export interface Principal {
  username: string;
  groups: string[];
  // True only for the synthetic plaintext-mode free-pass /auth/session hands back when nobody's
  // actually logged in (see FafnirServer#handleAuthSession) -- lets the login page tell "there's
  // nothing to redirect for" apart from "an operator is actually signed in".
  anonymous?: boolean;
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
