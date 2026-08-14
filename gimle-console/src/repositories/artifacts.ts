import type { ArtifactVersion } from "@/types";
import { delay } from "./util";

/**
 * Andvari's catalog is two calls deep by design -- {@link fetchCatalog} lists module ids only, and
 * a module's stored versions (with their checksums, sizes and provenance) come from a separate
 * {@link fetchVersions} call for the one module the operator actually opened. There is no
 * fetch-everything shape: a registry holding hundreds of modules would otherwise return every
 * version of every one of them to render a single list.
 */
export interface ArtifactsRepository {
  fetchCatalog(): Promise<string[]>;
  fetchVersions(moduleId: string): Promise<ArtifactVersion[]>;
  remove(moduleId: string, version: string): Promise<void>;
}

const HOUR = 3600_000;
const DAY = 24 * HOUR;

function mockSha(seed: string): string {
  // Deterministic filler so mock rows look like real digests without pulling in a hash library.
  let h = 0x811c9dc5;
  let out = "";
  for (let i = 0; out.length < 64; i++) {
    for (const c of `${seed}:${i}`) {
      h = Math.imul(h ^ c.charCodeAt(0), 0x01000193) >>> 0;
    }
    out += h.toString(16).padStart(8, "0");
  }
  return out.slice(0, 64);
}

function mockVersion(
  moduleId: string,
  version: string,
  sizeBytes: number,
  ageMillis: number,
  pushedBy: string,
): ArtifactVersion {
  return {
    moduleId,
    version,
    sha256: mockSha(`${moduleId}@${version}`),
    sizeBytes,
    pushedAtEpochMilli: Date.parse("2026-08-01T09:00:00Z") - ageMillis,
    pushedBy,
  };
}

const mockCatalog: Record<string, ArtifactVersion[]> = {
  "greeter-provider": [
    mockVersion("greeter-provider", "1.0.0", 18_432, 30 * DAY, "admin"),
    mockVersion("greeter-provider", "1.1.0", 19_104, 9 * DAY, "ci-publisher"),
    mockVersion("greeter-provider", "1.2.0-SNAPSHOT", 19_688, 4 * HOUR, "ci-publisher"),
  ],
  "greeter-consumer": [
    mockVersion("greeter-consumer", "1.0.0", 16_960, 30 * DAY, "admin"),
    mockVersion("greeter-consumer", "1.1.0", 17_512, 9 * DAY, "ci-publisher"),
  ],
  "billing-ledger": [
    mockVersion("billing-ledger", "2.4.1", 4_718_592, 62 * DAY, "release-bot"),
    mockVersion("billing-ledger", "2.5.0", 4_915_200, 11 * DAY, "release-bot"),
    mockVersion("billing-ledger", "2.5.1", 4_923_392, 2 * DAY, "hjalmar"),
  ],
  "edge-router": [mockVersion("edge-router", "0.9.0", 1_204_224, 5 * DAY, "hjalmar")],
  "report-batch": [
    mockVersion("report-batch", "3.0.0", 812_032, 120 * DAY, "admin"),
    mockVersion("report-batch", "3.0.1", 813_056, 47 * DAY, "release-bot"),
  ],
};

export class MockArtifactsRepository implements ArtifactsRepository {
  async fetchCatalog(): Promise<string[]> {
    return delay(Object.keys(mockCatalog).sort());
  }

  async fetchVersions(moduleId: string): Promise<ArtifactVersion[]> {
    const versions = mockCatalog[moduleId];
    if (!versions) throw new Error(`no versions stored for ${moduleId}`);
    return delay([...versions]);
  }

  async remove(moduleId: string, version: string): Promise<void> {
    const versions = mockCatalog[moduleId];
    const index = versions?.findIndex((v) => v.version === version) ?? -1;
    if (!versions || index < 0) {
      throw new Error(`no artifact stored for ${moduleId}:${version}`);
    }
    versions.splice(index, 1);
    // The registry drops a module from the catalog once its last version is gone -- there is no
    // such thing as a module with zero stored versions.
    if (versions.length === 0) delete mockCatalog[moduleId];
    return delay(undefined);
  }
}
