// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { createBlueprint, createNode, type Blueprint, type MachineData } from "@/lib/blueprint";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { Inspector } from "./Inspector";

afterEach(cleanup);

beforeEach(() => {
  useValidationStore.setState({ problems: [], serverProblems: [] });
});

function blueprintWithAnUnplacedStore(): {
  blueprint: Blueprint;
  storeId: string;
  machineId: string;
} {
  const blueprint = createBlueprint("test", { empty: true });
  const machine = createNode("machine", { x: 0, y: 0 });
  const store = createNode("store", { x: 100, y: 0 });
  blueprint.nodes = [machine, store];
  return { blueprint, storeId: store.id, machineId: machine.id };
}

describe("LinksSection's Add link control", () => {
  it("creates a link entirely via the control, round-tripping the same way a canvas drag would", () => {
    const { blueprint, storeId, machineId } = blueprintWithAnUnplacedStore();
    useBlueprintStore.setState({
      blueprint,
      selectedId: storeId,
      selectedIds: [storeId],
      past: [],
      future: [],
      dirty: false,
    });

    render(<Inspector blueprint={blueprint} />);

    expect(useBlueprintStore.getState().blueprint?.edges).toHaveLength(0);

    fireEvent.click(screen.getByRole("button", { name: /Add link/ }));

    const after = useBlueprintStore.getState().blueprint!;
    expect(after.edges).toEqual([
      expect.objectContaining({ kind: "placedOn", source: storeId, target: machineId }),
    ]);
    // The same field a drag-created placedOn edge would set (see connect() in useBlueprintStore).
    expect((after.nodes.find((n) => n.id === storeId)!.data as { machine?: string }).machine).toBe(
      (blueprint.nodes.find((n) => n.id === machineId)!.data as MachineData).name,
    );
  });

  it("offers nothing to add once every legal link already exists", () => {
    const { blueprint, storeId, machineId } = blueprintWithAnUnplacedStore();
    blueprint.edges = [{ id: "e1", kind: "placedOn", source: storeId, target: machineId }];
    useBlueprintStore.setState({
      blueprint,
      selectedId: storeId,
      selectedIds: [storeId],
      past: [],
      future: [],
      dirty: false,
    });

    render(<Inspector blueprint={blueprint} />);

    expect(screen.queryByRole("button", { name: /Add link/ })).toBeNull();
  });
});
