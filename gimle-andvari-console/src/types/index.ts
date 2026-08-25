export interface Principal {
  username: string;
  groups: string[];
}

export type TransportProtocol = "PLAINTEXT" | "TLS";

export interface RegistryStatus {
  uptimeSeconds: number;
  transportProtocol: TransportProtocol;
  moduleCount: number;
}

export interface ArtifactVersion {
  moduleId: string;
  version: string;
  sha256: string;
  sizeBytes: number;
  pushedAtEpochMilli: number;
  pushedBy: string;
  /** "JAR" (a single module/vessel jar) or "BUNDLE" (a zipped multi-file application). */
  kind?: "JAR" | "BUNDLE";
}

export interface ArtifactCatalogEntry {
  moduleId: string;
  versions: ArtifactVersion[];
}

export interface ArtifactPushResult extends ArtifactVersion {
  created: boolean;
}
