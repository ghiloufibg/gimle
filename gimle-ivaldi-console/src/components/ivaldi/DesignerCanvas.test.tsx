// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createBlueprint, createNode, type Blueprint } from "@/lib/blueprint";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { DesignerCanvas } from "./DesignerCanvas";

afterEach(cleanup);

beforeEach(() => {
  // @xyflow/react measures nodes via ResizeObserver, absent from jsdom.
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  useValidationStore.setState({ problems: [], serverProblems: [] });
});

function blueprintWithMachine(): { blueprint: Blueprint; machineId: string } {
  const blueprint = createBlueprint("test");
  const machine = createNode("machine", { x: 0, y: 0 });
  blueprint.nodes = [machine];
  return { blueprint, machineId: machine.id };
}

describe("DesignerCanvas", () => {
  it("stripes a node for a server-only (Hilmir) finding, not just a client-side one", () => {
    const { blueprint, machineId } = blueprintWithMachine();
    useBlueprintStore.setState({ blueprint, selectedId: null, selectedIds: [] });
    useValidationStore.setState({
      problems: [],
      serverProblems: [
        { code: "DUPLICATE_MACHINE", severity: "error", message: "collides", nodeId: machineId },
      ],
    });

    const { container } = render(<DesignerCanvas blueprint={blueprint} />);

    // Confirms the node actually rendered (not an empty canvas), then that MachineNode's own
    // error-severity stripe -- driven entirely off data.problems -- is present.
    expect(screen.getByText("machine-1")).toBeTruthy();
    expect(container.querySelector(".bg-status-bad")).toBeTruthy();
  });

  it("marks a problem node with a severity glyph, not color alone", () => {
    const { blueprint, machineId } = blueprintWithMachine();
    useBlueprintStore.setState({ blueprint, selectedId: null, selectedIds: [] });
    useValidationStore.setState({
      problems: [{ code: "X", severity: "warning", message: "y", nodeId: machineId }],
      serverProblems: [],
    });

    // Reaches into the DOM directly, like the stripe assertion above -- React Flow leaves an
    // unmeasured node `visibility: hidden` under jsdom's stubbed ResizeObserver, which a
    // role/text query would (rightly, for real accessibility) treat as not there at all.
    const { container } = render(<DesignerCanvas blueprint={blueprint} />);

    expect(container.querySelector('[aria-label="warning problem"]')).toBeTruthy();
  });

  it("shows no severity glyph on a clean node", () => {
    const { blueprint } = blueprintWithMachine();
    useBlueprintStore.setState({ blueprint, selectedId: null, selectedIds: [] });

    const { container } = render(<DesignerCanvas blueprint={blueprint} />);

    expect(container.querySelector('[role="img"]')).toBeNull();
  });
});

describe("DesignerCanvas's empty-canvas starter cluster", () => {
  it("scopes the copied Fafnir node's keyFile to the real, already-persisted blueprint's own id", () => {
    const blueprint = createBlueprint("real", { empty: true });
    useBlueprintStore.setState({ blueprint, selectedId: null, selectedIds: [] });

    render(<DesignerCanvas blueprint={blueprint} />);
    fireEvent.click(screen.getByText("Add a minimal local cluster"));

    const fafnirNode = useBlueprintStore
      .getState()
      .blueprint!.nodes.find((n) => n.kind === "fafnir")!;
    expect((fafnirNode.data as { keyFile?: string }).keyFile).toBe(
      `~/.gimle/data/${blueprint.id}/fafnir.key`,
    );
  });
});
