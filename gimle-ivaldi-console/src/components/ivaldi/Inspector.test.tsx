// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { createBlueprint, createNode, type Blueprint } from "@/lib/blueprint";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { Inspector } from "./Inspector";

afterEach(cleanup);

function blueprintWithMachine(): { blueprint: Blueprint; machineId: string } {
  const blueprint = createBlueprint("test");
  const machine = createNode("machine", { x: 0, y: 0 });
  blueprint.nodes = [machine];
  return { blueprint, machineId: machine.id };
}

beforeEach(() => {
  useValidationStore.setState({ problems: [], serverProblems: [] });
});

describe("Inspector", () => {
  it("shows a server-only (Hilmir) finding on the field it targets, not just the client-side ones", () => {
    const { blueprint, machineId } = blueprintWithMachine();
    useBlueprintStore.setState({ blueprint, selectedId: machineId, selectedIds: [machineId] });
    useValidationStore.setState({
      problems: [],
      serverProblems: [
        {
          code: "DUPLICATE_MACHINE",
          severity: "error",
          message: "Hilmir says this machine name collides",
          nodeId: machineId,
        },
      ],
    });

    render(<Inspector blueprint={blueprint} />);

    // Shown twice by design: once in the node's own "Problems" summary panel, and once inline on
    // the Name field the finding targets -- the field-level surfacing this test exists to prove.
    expect(screen.getAllByText(/Hilmir says this machine name collides/).length).toBeGreaterThan(0);
  });

  it("shows a MODULE_VERSION_MISMATCH finding inline on the Module version field", () => {
    const blueprint = createBlueprint("test", { empty: true });
    const deployment = createNode("deployment", { x: 0, y: 0 });
    blueprint.nodes = [deployment];
    useBlueprintStore.setState({
      blueprint,
      selectedId: deployment.id,
      selectedIds: [deployment.id],
    });
    useValidationStore.setState({
      problems: [],
      serverProblems: [
        {
          code: "MODULE_VERSION_MISMATCH",
          severity: "error",
          message: "declared module version doesn't match the jar's own",
          nodeId: deployment.id,
        },
      ],
    });

    render(<Inspector blueprint={blueprint} />);

    // Twice: once in the node's own "Problems" summary panel, once inline on the Module version
    // field itself -- the field-level surfacing this test exists to prove actually happened.
    expect(screen.getAllByText(/declared module version doesn't match the jar's own/)).toHaveLength(
      2,
    );
  });
});
