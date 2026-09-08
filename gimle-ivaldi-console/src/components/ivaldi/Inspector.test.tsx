// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  createBlueprint,
  createNode,
  type Blueprint,
  type NetworkPolicyData,
} from "@/lib/blueprint";
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

describe("NetworkPolicy restrictIngress toggle", () => {
  function blueprintWithPolicy(data: Partial<NetworkPolicyData> = {}) {
    const blueprint = createBlueprint("test", { empty: true });
    const policy = createNode("networkPolicy", { x: 0, y: 0 });
    policy.data = { ...policy.data, ...data };
    blueprint.nodes = [policy];
    return { blueprint, policy };
  }

  it("hides the allowed-caller-tenants field and shows an unrestricted hint when off", () => {
    const { blueprint, policy } = blueprintWithPolicy({ restrictIngress: false });
    useBlueprintStore.setState({ blueprint, selectedId: policy.id, selectedIds: [policy.id] });

    render(<Inspector blueprint={blueprint} />);

    expect(screen.queryByText("Allowed caller tenants")).toBeNull();
    expect(screen.getByText(/Inbound callers are unrestricted/)).toBeTruthy();
  });

  it("shows the allowed-caller-tenants field when on", () => {
    const { blueprint, policy } = blueprintWithPolicy({ restrictIngress: true });
    useBlueprintStore.setState({ blueprint, selectedId: policy.id, selectedIds: [policy.id] });

    render(<Inspector blueprint={blueprint} />);

    expect(screen.getByText("Allowed caller tenants")).toBeTruthy();
  });

  it("flips the node's own restrictIngress field when the checkbox is toggled on", () => {
    const { blueprint, policy } = blueprintWithPolicy({ restrictIngress: false });
    useBlueprintStore.setState({ blueprint, selectedId: policy.id, selectedIds: [policy.id] });

    render(<Inspector blueprint={blueprint} />);
    fireEvent.click(screen.getByLabelText("Restrict inbound callers"));

    const updated = useBlueprintStore
      .getState()
      .blueprint!.nodes.find((n) => n.id === policy.id)!.data as NetworkPolicyData;
    expect(updated.restrictIngress).toBe(true);
  });
});
