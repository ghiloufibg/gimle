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

/** What shape a secret's plaintext is declared to have -- mirrors Fafnir's own SecretType enum. */
export type SecretType = "opaque" | "pem-certificate" | "pem-private-key";

export interface SecretValue {
  tenantId: string;
  key: string;
  version: number;
  value: string; // already decoded to plain text by the repository layer
  type: SecretType;
}

/** One entry of a secret's version history, as returned by GET .../{key}/versions. */
export interface SecretVersion {
  version: number;
  author: string;
  writtenAtEpochMilli: number;
  type: SecretType;
}
