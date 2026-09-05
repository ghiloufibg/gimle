import {
  Background,
  BackgroundVariant,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useCallback, useEffect, useMemo, useRef } from "react";
import { toast } from "sonner";

import { canvasBridge } from "@/lib/canvasBridge";
import {
  createBlueprint,
  EDGE_LABELS,
  KIND_LABELS,
  type Blueprint,
  type NodeKind,
} from "@/lib/blueprint";
import { effectiveMachine, effectiveTenant } from "@/lib/effective";
import { isPlacedRole, isTenantScoped } from "@/lib/blueprint";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { keyFact, nodeTypes, type IvaldiNodeData } from "./CanvasNodes";

function labelOf(kind: NodeKind, data: unknown): string {
  const d = (data ?? {}) as Record<string, unknown>;
  const named = [d.name, d.id, d.nodeId, d.key].find(
    (v) => typeof v === "string" && v.trim() !== "",
  ) as string | undefined;
  return named ?? KIND_LABELS[kind];
}

/** A node with a missing nested object must not take the canvas down with it. */
function safeFact(kind: NodeKind, data: unknown): string {
  try {
    return keyFact(kind, data as never);
  } catch {
    return "incomplete";
  }
}

function CanvasInner({ blueprint }: { blueprint: Blueprint }) {
  const wrapper = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition, fitView, getViewport } = useReactFlow();
  const selectedIds = useBlueprintStore((s) => s.selectedIds);
  const selectedEdgeIds = useBlueprintStore((s) => s.selectedEdgeIds);
  const setSelection = useBlueprintStore((s) => s.setSelection);
  const moveNode = useBlueprintStore((s) => s.moveNode);
  const addNode = useBlueprintStore((s) => s.addNode);
  const connect = useBlueprintStore((s) => s.connect);
  const removeNodes = useBlueprintStore((s) => s.removeNodes);
  const removeEdges = useBlueprintStore((s) => s.removeEdges);
  const problems = useValidationStore((s) => s.problems);

  useEffect(() => {
    canvasBridge.fit = () => fitView({ padding: 0.2, duration: 200 });
    canvasBridge.center = () => {
      const rect = wrapper.current?.getBoundingClientRect();
      if (!rect) {
        const vp = getViewport();
        return { x: -vp.x / vp.zoom + 120, y: -vp.y / vp.zoom + 120 };
      }
      return screenToFlowPosition({
        x: rect.left + rect.width / 2,
        y: rect.top + rect.height / 2,
      });
    };
  }, [fitView, getViewport, screenToFlowPosition]);

  const nodes: Node[] = useMemo(() => {
    // Machines are drawn first so their group frame never covers the roles
    // that sit inside them.
    const ordered = [...blueprint.nodes].sort(
      (a, b) => (a.kind === "machine" ? 0 : 1) - (b.kind === "machine" ? 0 : 1),
    );
    return ordered.map((n) => ({
      id: n.id,
      type: n.kind === "machine" ? "machine" : "resource",
      position: n.position,
      selected: selectedIds.includes(n.id),
      zIndex: n.kind === "machine" ? 0 : 1,
      data: {
        kind: n.kind,
        label: labelOf(n.kind, n.data),
        fact: safeFact(n.kind, n.data),
        problems: problems.filter((p) => p.nodeId === n.id),
        selected: selectedIds.includes(n.id),
        where: isPlacedRole(n.kind)
          ? `on ${effectiveMachine(blueprint, n).value || "no machine"}`
          : isTenantScoped(n.kind)
            ? `in ${effectiveTenant(blueprint, n).value || "no tenant"}`
            : undefined,
      } satisfies IvaldiNodeData,
    }));
  }, [blueprint, problems, selectedIds]);

  const edges: Edge[] = useMemo(
    () =>
      blueprint.edges.map((e) => {
        const selected = selectedEdgeIds.includes(e.id);
        return {
          id: e.id,
          source: e.source,
          target: e.target,
          label: EDGE_LABELS[e.kind],
          animated: false,
          selected,
          selectable: true,
          deletable: true,
          style: {
            stroke: selected ? "var(--color-primary)" : "var(--color-border)",
            strokeWidth: selected ? 2 : 1.2,
          },
          labelStyle: {
            fill: "var(--color-muted-foreground)",
            fontSize: 9,
            fontFamily: "var(--font-mono)",
            textTransform: "uppercase",
            letterSpacing: "0.12em",
          },
          labelBgStyle: { fill: "var(--color-background)" },
        };
      }),
    [blueprint.edges, selectedEdgeIds],
  );

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      const store = useBlueprintStore.getState();
      let nodeIds = [...store.selectedIds];
      let touched = false;
      for (const change of changes) {
        if (change.type === "position" && change.position) moveNode(change.id, change.position);
        // The node list is controlled, so selection only sticks if we apply it.
        if (change.type === "select") {
          touched = true;
          nodeIds = change.selected
            ? [...nodeIds.filter((id) => id !== change.id), change.id]
            : nodeIds.filter((id) => id !== change.id);
        }
      }
      if (touched) store.setSelection(nodeIds, []);
    },
    [moveNode],
  );

  const onConnect = useCallback(
    (params: Connection) => {
      if (!params.source || !params.target) return;
      const result = connect(params.source, params.target);
      if (!result.ok) toast.error("Link refused", { description: result.reason });
    },
    [connect],
  );

  return (
    <div ref={wrapper} className="h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onConnect={onConnect}
        elementsSelectable
        multiSelectionKeyCode={["Shift", "Meta", "Control"]}
        selectionKeyCode="Shift"
        deleteKeyCode={["Delete", "Backspace"]}
        onNodesDelete={(deleted) => {
          const ids = deleted.map((n) => n.id);
          const links = blueprint.edges.filter(
            (e) => ids.includes(e.source) || ids.includes(e.target),
          ).length;
          if (
            links > 0 &&
            !window.confirm(
              `Delete ${ids.length} node${ids.length === 1 ? "" : "s"} and ${links} link${links === 1 ? "" : "s"}? Ctrl+Z brings them back.`,
            )
          )
            return;
          removeNodes(ids);
        }}
        onEdgesDelete={(deleted) => removeEdges(deleted.map((e) => e.id))}
        onEdgeClick={(_, edge) => setSelection([], [edge.id])}
        onPaneClick={() => setSelection([], [])}
        onDragOver={(event) => {
          event.preventDefault();
          event.dataTransfer.dropEffect = "move";
        }}
        onDrop={(event) => {
          event.preventDefault();
          const kind = event.dataTransfer.getData("application/ivaldi-kind") as NodeKind;
          if (!kind) return;
          const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
          addNode(kind, position);
        }}
        fitView
        proOptions={{ hideAttribution: true }}
        minZoom={0.2}
        className="bg-background"
      >
        <Background
          variant={BackgroundVariant.Dots}
          gap={16}
          size={1}
          color="var(--color-border)"
        />
        <Controls className="!border !border-border !bg-card" showInteractive={false} />
      </ReactFlow>
    </div>
  );
}

export function DesignerCanvas({ blueprint }: { blueprint: Blueprint }) {
  if (blueprint.nodes.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-center">
        <div>
          <div className="hud-label">Empty canvas</div>
          <p className="mt-1 text-xs text-muted-foreground">
            Drag a machine from the palette, or start from a working one-machine cluster.
          </p>
          <button
            onClick={() => {
              const starter = createBlueprint(blueprint.name);
              useBlueprintStore.getState().patchBlueprint({
                nodes: starter.nodes,
                edges: starter.edges,
              });
            }}
            className="mt-3 inline-flex h-7 items-center rounded-sm border border-primary bg-primary px-2.5 font-mono text-[11px] text-primary-foreground"
          >
            Add a minimal local cluster
          </button>
        </div>
      </div>
    );
  }
  return (
    <ReactFlowProvider>
      <CanvasInner blueprint={blueprint} />
    </ReactFlowProvider>
  );
}
