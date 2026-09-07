// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
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
});
