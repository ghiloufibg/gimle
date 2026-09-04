import { describe, expect, it } from "vitest";

import { sampleBlueprints } from "./samples";
import { DEFAULT_PORTS, defaultPortFor, portClaims, portConflicts } from "./ports";

const [ordersPlatform, brokenExample] = sampleBlueprints();

describe("portClaims", () => {
  it("claims both store ports, the role ports, and each agent's gossip port", () => {
    const claims = portClaims(ordersPlatform!);
    const byWhat = (what: string) => claims.filter((c) => c.what === what);

    expect(byWhat("store raft")).toHaveLength(1);
    expect(byWhat("store client")).toHaveLength(1);
    expect(byWhat("controlPlane port")).toHaveLength(1);
    expect(byWhat("fafnir port")).toHaveLength(1);
    expect(byWhat("andvari port")).toHaveLength(1);
    expect(byWhat("agent gossip")).toHaveLength(2);
  });

  it("scopes claims to the machine each role is placed on", () => {
    const claim = portClaims(ordersPlatform!).find((c) => c.what === "controlPlane port");
    expect(claim?.machine).toBe("local");
    expect(claim?.port).toBe(8080);
  });
});

describe("portConflicts", () => {
  it("finds no conflicts on the clean sample", () => {
    expect(portConflicts(ordersPlatform!)).toHaveLength(0);
  });

  it("finds the two control planes sharing one port on the broken sample", () => {
    const conflicts = portConflicts(brokenExample!);
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0]).toHaveLength(2);
    expect(conflicts[0]!.every((c) => c.machine === "box" && c.port === 8080)).toBe(true);
  });
});

describe("defaultPortFor", () => {
  it("returns the platform default for each role kind that has a fixed default port", () => {
    expect(defaultPortFor("controlPlane")).toBe(DEFAULT_PORTS.controlPlane);
    expect(defaultPortFor("fafnir")).toBe(DEFAULT_PORTS.fafnir);
    expect(defaultPortFor("muninn")).toBe(DEFAULT_PORTS.muninn);
    expect(defaultPortFor("andvari")).toBe(DEFAULT_PORTS.andvari);
  });

  it("has no single default port for kinds with a compound or absent port shape", () => {
    expect(defaultPortFor("store")).toBeUndefined();
    expect(defaultPortFor("agent")).toBeUndefined();
    expect(defaultPortFor("machine")).toBeUndefined();
    expect(defaultPortFor("tenant")).toBeUndefined();
  });
});
