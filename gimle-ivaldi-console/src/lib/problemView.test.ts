import { describe, expect, it } from "vitest";

import type { Problem } from "./blueprint";
import { collapseProblems, suppressCascades, tally } from "./problemView";

const err = (code: string, message: string, nodeId?: string): Problem => ({
  code,
  severity: "error",
  message,
  nodeId,
});

describe("suppressCascades", () => {
  it("hides the roles with no machine when there is no machine at all", () => {
    const kept = suppressCascades([
      err("NO_MACHINES", "Topology declares no machines."),
      err("UNKNOWN_MACHINE", "store is not placed on any machine.", "n1"),
    ]);

    expect(kept.map((p) => p.code)).toEqual(["NO_MACHINES"]);
  });

  it("keeps an unplaced role when machines do exist", () => {
    const kept = suppressCascades([err("UNKNOWN_MACHINE", "store is not placed.", "n1")]);

    expect(kept.map((p) => p.code)).toEqual(["UNKNOWN_MACHINE"]);
  });

  it("hides a quota breach against a tenant whose quota is not a usable number", () => {
    const kept = suppressCascades([
      { ...err("QUOTA_NOT_POSITIVE", "Tenant quota memory must be greater than 0.", "t1") },
      { ...err("QUOTA_EXCEEDED", "Tenant t1 is over its memory quota.", "t1") },
    ]);

    expect(kept.map((p) => p.code)).toEqual(["QUOTA_NOT_POSITIVE"]);
  });

  it("leaves another tenant's quota breach alone", () => {
    const kept = suppressCascades([
      { ...err("QUOTA_NOT_POSITIVE", "Tenant quota memory must be greater than 0.", "t1") },
      { ...err("QUOTA_EXCEEDED", "Tenant t2 is over its memory quota.", "t2") },
    ]);

    expect(kept.map((p) => p.code)).toEqual(["QUOTA_NOT_POSITIVE", "QUOTA_EXCEEDED"]);
  });
});

describe("collapseProblems", () => {
  it("merges one fault reported against two nodes into a row naming both", () => {
    const rows = collapseProblems(
      [
        err("PORT_CONFLICT", "machine local port 8080 is claimed twice.", "a"),
        err("PORT_CONFLICT", "machine local port 8080 is claimed twice.", "b"),
      ],
      [],
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].nodeIds).toEqual(["a", "b"]);
  });

  it("keeps two different faults that happen to share a code", () => {
    const rows = collapseProblems(
      [
        err("UNKNOWN_MACHINE", 'store is placed on unknown machine "a".', "s"),
        err("UNKNOWN_MACHINE", 'fafnir is placed on unknown machine "b".', "f"),
      ],
      [],
    );

    expect(rows).toHaveLength(2);
  });

  it("names both validators when they word the same fault identically", () => {
    const same = err("NO_FAFNIR", "No Fafnir replica declared.");
    const rows = collapseProblems([same], [{ ...same, file: undefined }]);

    expect(rows).toHaveLength(1);
    expect(rows[0].sources).toEqual(["ivaldi", "hilmir"]);
  });

  it("shows both when the two validators word it differently, rather than dropping one", () => {
    const rows = collapseProblems(
      [err("NO_FAFNIR", "No Fafnir replica declared.")],
      [err("NO_FAFNIR", "no fafnir replicas declared")],
    );

    expect(rows).toHaveLength(2);
  });

  it("orders errors before warnings before notes", () => {
    const rows = collapseProblems(
      [
        { code: "C", severity: "info", message: "c" },
        { code: "B", severity: "warning", message: "b" },
        { code: "A", severity: "error", message: "a" },
      ],
      [],
    );

    expect(rows.map((r) => r.code)).toEqual(["A", "B", "C"]);
    expect(tally(rows)).toEqual({ errors: 1, warnings: 1, infos: 1 });
  });
});
