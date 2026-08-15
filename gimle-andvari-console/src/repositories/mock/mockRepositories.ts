import type {
  ArtifactCatalogEntry,
  ArtifactPushResult,
  ArtifactVersion,
  Principal,
  RegistryStatus,
} from "@/types";
import type { ArtifactsRepository, AuthRepository, StatusRepository } from "../types";
import { ApiError } from "../http/apiClient";
import { createSeedArtifacts } from "./mockData";

const delay = (ms = 260) => new Promise((resolve) => setTimeout(resolve, ms));

export class MockAuthRepository implements AuthRepository {
  private principal: Principal | null = { username: "anonymous", groups: [] };

  async session(): Promise<Principal | null> {
    await delay(180);
    return this.principal;
  }

  async login(username: string, password: string): Promise<Principal> {
    await delay();
    if (!username || !password) throw new ApiError(401, "invalid username or password");
    this.principal = { username, groups: ["operators"] };
    return this.principal;
  }

  async logout(): Promise<void> {
    await delay(120);
    this.principal = { username: "anonymous", groups: [] };
  }
}

export class MockStatusRepository implements StatusRepository {
  constructor(private readonly artifacts: MockArtifactsRepository) {}

  async status(): Promise<RegistryStatus> {
    await delay(150);
    return {
      uptimeSeconds: 3 * 86_400 + 7 * 3600 + 22 * 60,
      transportProtocol: "PLAINTEXT",
      moduleCount: this.artifacts.moduleCount(),
    };
  }
}

export class MockArtifactsRepository implements ArtifactsRepository {
  private store: Record<string, ArtifactVersion[]> = createSeedArtifacts();

  moduleCount(): number {
    return Object.keys(this.store).length;
  }

  async catalog(): Promise<string[]> {
    await delay();
    return Object.keys(this.store).sort();
  }

  async module(moduleId: string): Promise<ArtifactCatalogEntry> {
    await delay();
    const versions = this.store[moduleId];
    if (!versions) throw new ApiError(404, `unknown module ${moduleId}`);
    return { moduleId, versions: [...versions] };
  }

  async push(moduleId: string, version: string, file: File): Promise<ArtifactPushResult> {
    await delay(700);
    const existing = this.store[moduleId]?.find((entry) => entry.version === version);
    if (existing) {
      throw new ApiError(
        409,
        `artifact ${moduleId}:${version} already exists with sha256 ${existing.sha256} -- a ` +
          "stored version is immutable; push the changed jar as a new version",
      );
    }
    const pushed: ArtifactVersion = {
      moduleId,
      version,
      sha256: randomSha256(),
      sizeBytes: file.size,
      pushedAtEpochMilli: Date.now(),
      pushedBy: "bg.ghiloufi",
    };
    this.store[moduleId] = [...(this.store[moduleId] ?? []), pushed];
    return { ...pushed, created: true };
  }

  async remove(moduleId: string, version: string): Promise<void> {
    await delay(320);
    const versions = this.store[moduleId];
    if (!versions?.some((entry) => entry.version === version)) {
      throw new ApiError(404, `unknown artifact ${moduleId}:${version}`);
    }
    const remaining = versions.filter((entry) => entry.version !== version);
    if (remaining.length === 0) delete this.store[moduleId];
    else this.store[moduleId] = remaining;
  }

  downloadUrl(moduleId: string, version: string): string {
    return `/artifacts/${moduleId}/${version}`;
  }

  async download(moduleId: string, version: string): Promise<Blob> {
    await delay(300);
    return new Blob([`mock jar bytes for ${moduleId}:${version}`], {
      type: "application/java-archive",
    });
  }
}

function randomSha256(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
