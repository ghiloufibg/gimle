import type { ArtifactCatalogEntry, ArtifactPushResult, Principal, RegistryStatus } from "@/types";

export interface AuthRepository {
  session(): Promise<Principal | null>;
  login(username: string, password: string): Promise<Principal>;
  logout(): Promise<void>;
}

export interface StatusRepository {
  status(): Promise<RegistryStatus>;
}

export interface ArtifactsRepository {
  catalog(): Promise<string[]>;
  module(moduleId: string): Promise<ArtifactCatalogEntry>;
  push(moduleId: string, version: string, file: File): Promise<ArtifactPushResult>;
  remove(moduleId: string, version: string): Promise<void>;
  downloadUrl(moduleId: string, version: string): string;
  download(moduleId: string, version: string): Promise<Blob>;
}
