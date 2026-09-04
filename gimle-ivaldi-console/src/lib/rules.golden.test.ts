import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import type { Blueprint } from "./blueprint";
import { renderFiles } from "./render";
import { validateTopology } from "./rules";
import { sampleBlueprints } from "./samples";

/**
 * Proves tier 1 (this module's own `validateTopology`) and tier 2 (the real
 * `com.gimle.hilmir.validate.TopologyValidator`, run by
 * `gimle-ivaldi`'s `TopologyTierAgreementTest`) genuinely agree on the codes
 * they report for the same topology, instead of trusting that two
 * independently-written rule catalogs happen to line up.
 *
 * Both sides read the exact same fixture file under
 * `gimle-ivaldi/src/test/resources/golden/` -- this repo's one canonical copy,
 * not two hand-maintained ones that could quietly drift apart. This file's own
 * job is twofold: (a) the drift guard -- assert `renderFiles()`'s current
 * `topology.yaml` output for each sample still matches that committed fixture
 * byte-for-byte, so a change to a sample or to `render.ts` itself is caught
 * here rather than silently invalidating what the Java-side test checks; and
 * (b) assert tier 1's own codes for the same Blueprint match the identical
 * hardcoded set `TopologyTierAgreementTest` asserts tier 2 reports for that
 * fixture. If a topology rule is ever added to one side and not the other, one
 * of these two tests starts failing.
 *
 * Compared as a distinct code set, not a duplicate-counting list: tier 1
 * deliberately emits one `Problem` per implicated canvas node for a
 * colocation/conflict rule (each carries its own `nodeId` so the Designer can
 * highlight every offending node), while `TopologyValidator` emits exactly one
 * finding per situation -- it has no per-node concept. Two agents on one
 * machine is genuinely one `AGENTS_COLOCATED` finding there and two `Problem`s
 * here; what both sides must agree on is whether the code fired at all, not
 * how many times.
 */

const GOLDEN_DIR = path.resolve(
  import.meta.dirname,
  "../../../gimle-ivaldi/src/test/resources/golden",
);

function fixture(name: string): string {
  return readFileSync(path.join(GOLDEN_DIR, name), "utf8");
}

const [ordersPlatform, brokenExample] = sampleBlueprints();

function renderedTopology(bp: Blueprint): string {
  return renderFiles(bp).find((f) => f.path === "topology.yaml")!.content;
}

describe("topology tier-1/tier-2 golden agreement", () => {
  it("orders-platform-local: render.ts output matches the committed fixture tier 2 reads", () => {
    expect(renderedTopology(ordersPlatform!)).toBe(fixture("orders-platform-local-topology.yaml"));
  });

  it("orders-platform-local: tier 1's topology codes match tier 2's (TopologyTierAgreementTest)", () => {
    const codes = new Set(validateTopology(ordersPlatform!).map((p) => p.code));
    expect(codes).toEqual(new Set(["AGENTS_COLOCATED", "SINGLE_STORE", "SINGLE_CONTROL_PLANE"]));
  });

  it("broken-example: render.ts output matches the committed fixture tier 2 reads", () => {
    expect(renderedTopology(brokenExample!)).toBe(fixture("broken-example-topology.yaml"));
  });

  it("broken-example: tier 1's topology codes match tier 2's (TopologyTierAgreementTest)", () => {
    const codes = new Set(validateTopology(brokenExample!).map((p) => p.code));
    expect(codes).toEqual(
      new Set([
        "PORT_CONFLICT",
        "REPLICAS_COLOCATED",
        "MTLS_NO_MATERIAL_DIR",
        "MTLS_IP_LITERAL_HOST",
        "SINGLE_STORE",
      ]),
    );
  });
});
