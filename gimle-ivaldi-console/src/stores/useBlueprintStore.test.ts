import { beforeEach, describe, expect, it } from "vitest";

import type { Blueprint, BlueprintEdge, BlueprintNode } from "@/lib/blueprint";

import { useBlueprintStore } from "./useBlueprintStore";

// "machine" throughout, regardless of what an edge between two of them would mean semantically:
// these tests exercise the store's own mechanics (commit/undo/drag), never rules.ts's cross-node
// validation, and a real tenant/workload pair would need a fully-populated quota/resources shape
// just to avoid tripping that validator's own field access on the way to a result no test here
// reads.
function node(id: string, kind: BlueprintNode["kind"] = "machine"): BlueprintNode {
  return { id, kind, position: { x: 0, y: 0 }, data: { name: id, host: "127.0.0.1" } as never };
}

function edge(id: string, source: string, target: string): BlueprintEdge {
  return { id, kind: "belongsTo", source, target };
}

function blueprintWith(nodes: BlueprintNode[], edges: BlueprintEdge[]): Blueprint {
  return {
    id: "bp-test",
    name: "test",
    version: "1.0.0",
    transport: "plaintext",
    runtime: { dataRoot: "~/.gimle/data" },
    nodes,
    edges,
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

beforeEach(() => {
  useBlueprintStore.setState({
    blueprint: null,
    selectedId: null,
    selectedIds: [],
    selectedEdgeIds: [],
    dirty: false,
    past: [],
    future: [],
  });
});

describe("useBlueprintStore.removeNodesAndEdges", () => {
  it("removes the node and its cascaded edges in one commit, undoable in a single step", () => {
    const bp = blueprintWith(
      [node("t1"), node("n1"), node("n2")],
      [edge("e1", "n1", "t1"), edge("e2", "n2", "t1")],
    );
    useBlueprintStore.setState({ blueprint: bp });

    // Mirrors what the canvas's combined onDelete callback passes: the node plus every edge React
    // Flow already resolved as connected to it, both removed together.
    useBlueprintStore.getState().removeNodesAndEdges(["n1"], ["e1"]);

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.nodes.map((n) => n.id)).toEqual(["t1", "n2"]);
    expect(after.edges.map((e) => e.id)).toEqual(["e2"]);
    expect(useBlueprintStore.getState().past).toHaveLength(1);

    useBlueprintStore.getState().undo();

    const restored = useBlueprintStore.getState().blueprint!;
    expect(restored.nodes.map((n) => n.id)).toEqual(["t1", "n1", "n2"]);
    expect(restored.edges.map((e) => e.id)).toEqual(["e1", "e2"]);
  });

  it("also removes an edge named explicitly that isn't connected to any deleted node", () => {
    const bp = blueprintWith(
      [node("t1"), node("n1"), node("n2")],
      [edge("e1", "n1", "t1"), edge("e2", "n2", "t1")],
    );
    useBlueprintStore.setState({ blueprint: bp });

    // A user can multi-select an unrelated edge alongside a node before pressing Delete.
    useBlueprintStore.getState().removeNodesAndEdges(["n1"], ["e1", "e2"]);

    expect(useBlueprintStore.getState().blueprint!.edges).toEqual([]);
  });

  it("does nothing when given no nodes and no edges", () => {
    const bp = blueprintWith([node("n1")], []);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().removeNodesAndEdges([], []);

    expect(useBlueprintStore.getState().blueprint).toBe(bp);
    expect(useBlueprintStore.getState().past).toHaveLength(0);
  });
});

// connect() copies the target's own name/id into the source's plain-text machine/tenantId field
// (see useBlueprintStore.ts's own comment on linkedFieldFor) -- these exercise that the copy is
// cleared, not left stale, wherever the placedOn/belongsTo edge that made it read-only can
// disappear from under it.
describe("useBlueprintStore clears a placedOn/belongsTo edge's copied field once the edge is gone", () => {
  function workload(id: string, tenantId: string): BlueprintNode {
    return {
      id,
      kind: "deployment",
      position: { x: 0, y: 0 },
      data: { name: id, tenantId } as never,
    };
  }
  function role(id: string, machine: string): BlueprintNode {
    return { id, kind: "agent", position: { x: 0, y: 0 }, data: { name: id, machine } as never };
  }
  function placedOn(id: string, source: string, target: string): BlueprintEdge {
    return { id, kind: "placedOn", source, target };
  }
  // A bare node("id", "tenant") has no `quota` at all, and validateApplication (run on every
  // commit here, via revalidate) reads straight through it for any workload naming this tenant --
  // see this file's own top-of-file comment on why a tenant fixture needs its real shape.
  function tenant(id: string): BlueprintNode {
    return {
      id,
      kind: "tenant",
      position: { x: 0, y: 0 },
      data: {
        id,
        quota: { maxMemoryBytes: 1024 * 1024 * 1024, maxCpuMillicores: 4000, maxInstances: 20 },
      } as never,
    };
  }

  it("disconnect clears the source node's own tenantId copy, not just the edge", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().disconnect("e1");

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.edges).toEqual([]);
    expect((after.nodes.find((n) => n.id === "w1")!.data as { tenantId: string }).tenantId).toBe(
      "",
    );
  });

  it("disconnect clears a placedOn edge's own copied machine field the same way", () => {
    const bp = blueprintWith(
      [role("r1", "local"), node("m1", "machine")],
      [placedOn("e1", "r1", "m1")],
    );
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().disconnect("e1");

    const after = useBlueprintStore.getState().blueprint!;
    expect((after.nodes.find((n) => n.id === "r1")!.data as { machine: string }).machine).toBe("");
  });

  it("removeNodesAndEdges clears a surviving source's own copy when its link target is the one deleted", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    // Deletes the tenant itself, cascading the edge -- w1 survives and must not show "acme" any more.
    useBlueprintStore.getState().removeNodesAndEdges(["t1"], ["e1"]);

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.nodes.map((n) => n.id)).toEqual(["w1"]);
    expect((after.nodes[0].data as { tenantId: string }).tenantId).toBe("");
  });

  it("does not bother clearing a field on a node that is itself being deleted", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    // Deletes the workload itself; only the tenant survives, untouched.
    useBlueprintStore.getState().removeNodesAndEdges(["w1"], ["e1"]);

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.nodes.map((n) => n.id)).toEqual(["t1"]);
  });

  // removeNode is the Inspector's own "Delete node" button -- a separate path from the canvas's
  // combined removeNodesAndEdges gesture, and easy to leave behind when only one of the two gets
  // this fix (as happened once already: the canvas path was fixed and live-verified, but the
  // Inspector button's own delete still left the stale tenantId behind until this test caught it).
  it("removeNode (the Inspector's own delete button) clears a surviving source's copy too", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().removeNode("t1");

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.nodes.map((n) => n.id)).toEqual(["w1"]);
    expect((after.nodes[0].data as { tenantId: string }).tenantId).toBe("");
  });

  it("removeNodes (plural) clears a surviving source's copy the same way", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().removeNodes(["t1"]);

    const after = useBlueprintStore.getState().blueprint!;
    expect((after.nodes[0].data as { tenantId: string }).tenantId).toBe("");
  });

  it("removeEdges (plural) clears the source's copy the same way disconnect does", () => {
    const bp = blueprintWith([workload("w1", "acme"), tenant("t1")], [edge("e1", "w1", "t1")]);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().removeEdges(["e1"]);

    const after = useBlueprintStore.getState().blueprint!;
    expect((after.nodes.find((n) => n.id === "w1")!.data as { tenantId: string }).tenantId).toBe(
      "",
    );
  });
});

describe("useBlueprintStore drag undo (beginDrag/endDrag)", () => {
  it("checkpoints a drag as one undo step regardless of how many positions moveNode touched", () => {
    const bp = blueprintWith([node("n1"), node("n2")], []);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().beginDrag();
    // A drag reports several intermediate positions before it ends -- none of these may cost
    // their own undo step, or one drag would take many Ctrl+Z presses to unwind.
    useBlueprintStore.getState().moveNode("n1", { x: 10, y: 10 });
    useBlueprintStore.getState().moveNode("n1", { x: 20, y: 20 });
    useBlueprintStore.getState().moveNode("n2", { x: 5, y: 5 });
    expect(useBlueprintStore.getState().past).toHaveLength(0);
    useBlueprintStore.getState().endDrag();

    expect(useBlueprintStore.getState().past).toHaveLength(1);
    const moved = useBlueprintStore.getState().blueprint!;
    expect(moved.nodes.find((n) => n.id === "n1")!.position).toEqual({ x: 20, y: 20 });

    useBlueprintStore.getState().undo();

    const restored = useBlueprintStore.getState().blueprint!;
    expect(restored.nodes.find((n) => n.id === "n1")!.position).toEqual({ x: 0, y: 0 });
    expect(restored.nodes.find((n) => n.id === "n2")!.position).toEqual({ x: 0, y: 0 });
  });

  it("a click that never moves anything leaves no undo step", () => {
    const bp = blueprintWith([node("n1")], []);
    useBlueprintStore.setState({ blueprint: bp });

    useBlueprintStore.getState().beginDrag();
    useBlueprintStore.getState().endDrag();

    expect(useBlueprintStore.getState().past).toHaveLength(0);
  });
});

describe("useBlueprintStore.addNode", () => {
  it("nudges a node away from an existing one at the exact same requested position", () => {
    const bp = blueprintWith([node("n1")], []); // n1 sits at {x: 0, y: 0}
    useBlueprintStore.setState({ blueprint: bp });

    // Mirrors the palette's own click-to-add, which always requests the same canvas-center point.
    const added = useBlueprintStore.getState().addNode("machine", { x: 0, y: 0 });

    expect(added).not.toBeNull();
    expect(added!.position).not.toEqual({ x: 0, y: 0 });
    // n1 itself is left exactly where it was.
    const n1After = useBlueprintStore.getState().blueprint!.nodes.find((n) => n.id === "n1")!;
    expect(n1After.position).toEqual({ x: 0, y: 0 });
  });

  it("keeps nudging past more than one occupied spot in a row", () => {
    const bp = blueprintWith([node("n1"), node("n2")], []);
    useBlueprintStore.setState({ blueprint: bp });
    // n2 already sits wherever n1's own nudge would have landed one step out.
    useBlueprintStore.setState((s) => ({
      blueprint: {
        ...s.blueprint!,
        nodes: s.blueprint!.nodes.map((n) =>
          n.id === "n2" ? { ...n, position: { x: 32, y: 32 } } : n,
        ),
      },
    }));

    const added = useBlueprintStore.getState().addNode("machine", { x: 0, y: 0 });

    const occupied = [
      { x: 0, y: 0 },
      { x: 32, y: 32 },
    ];
    expect(occupied).not.toContainEqual(added!.position);
  });

  it("does not nudge a position nothing else occupies", () => {
    const bp = blueprintWith([node("n1")], []); // n1 sits at {x: 0, y: 0}
    useBlueprintStore.setState({ blueprint: bp });

    const added = useBlueprintStore.getState().addNode("machine", { x: 500, y: 500 });

    expect(added!.position).toEqual({ x: 500, y: 500 });
  });

  it("gives a second and third machine their own distinct loopback host, not a collision", () => {
    const bp = blueprintWith([], []);
    useBlueprintStore.setState({ blueprint: bp });

    const first = useBlueprintStore.getState().addNode("machine", { x: 0, y: 0 });
    const second = useBlueprintStore.getState().addNode("machine", { x: 100, y: 100 });
    const third = useBlueprintStore.getState().addNode("machine", { x: 200, y: 200 });

    expect((first!.data as { host: string }).host).toBe("127.0.0.1");
    expect((second!.data as { host: string }).host).toBe("127.0.0.2");
    expect((third!.data as { host: string }).host).toBe("127.0.0.3");
  });
});
