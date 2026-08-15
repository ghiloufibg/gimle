import type { ArtifactVersion } from "@/types";

const HEX = "0123456789abcdef";

function hash(seed: string): string {
  let value = 0;
  for (let i = 0; i < seed.length; i += 1) value = (value * 31 + seed.charCodeAt(i)) >>> 0;
  let out = "";
  for (let i = 0; i < 64; i += 1) {
    value = (value * 1664525 + 1013904223) >>> 0;
    out += HEX[value % 16];
  }
  return out;
}

const HOUR = 3_600_000;
const BASE_TIME = Date.UTC(2026, 6, 20, 9, 30, 0);

function version(
  moduleId: string,
  v: string,
  sizeBytes: number,
  hoursAgo: number,
  pushedBy: string,
): ArtifactVersion {
  return {
    moduleId,
    version: v,
    sha256: hash(`${moduleId}:${v}`),
    sizeBytes,
    pushedAtEpochMilli: BASE_TIME - hoursAgo * HOUR,
    pushedBy,
  };
}

export function createSeedArtifacts(): Record<string, ArtifactVersion[]> {
  return {
    "com.gimle.examples.greeter-provider": [
      version("com.gimle.examples.greeter-provider", "1.0.0", 48_512, 720, "bg.ghiloufi"),
      version("com.gimle.examples.greeter-provider", "1.1.0", 51_204, 168, "ci@gimle"),
      version("com.gimle.examples.greeter-provider", "1.2.0", 53_880, 6, "ci@gimle"),
    ],
    "com.gimle.examples.greeter-consumer": [
      version("com.gimle.examples.greeter-consumer", "1.0.0", 31_720, 700, "bg.ghiloufi"),
      version("com.gimle.examples.greeter-consumer", "1.1.0", 33_004, 30, "ci@gimle"),
    ],
    "com.gimle.runtime.module-loader": [
      version("com.gimle.runtime.module-loader", "0.9.3", 1_284_400, 480, "ci@gimle"),
      version("com.gimle.runtime.module-loader", "0.9.4", 1_301_112, 72, "ci@gimle"),
      version("com.gimle.runtime.module-loader", "1.0.0-rc1", 1_356_802, 2, "bg.ghiloufi"),
    ],
    "com.gimle.telemetry.hud-exporter": [
      version("com.gimle.telemetry.hud-exporter", "2.3.1", 482_048, 240, "ci@gimle"),
      version("com.gimle.telemetry.hud-exporter", "2.4.0", 494_930, 48, "ops@gimle"),
    ],
  };
}
