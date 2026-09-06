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
});
