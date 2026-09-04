import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useCallback, useMemo, useRef } from "react";
import { toast } from "sonner";

import { EDGE_LABELS, KIND_LABELS, type Blueprint, type NodeKind } from "@/lib/blueprint";
import { useBlueprintStore } from "@/stores/useBlueprintStore";
import { useValidationStore } from "@/stores/useValidationStore";

import { keyFact, nodeTypes, type IvaldiNodeData } from "./CanvasNodes";

function labelOf(kind: NodeKind, data: unknown): string {
  const d = data as Record<string, unknown>;
  return (
    (d.name as string) ??
    (d.id as string) ??
    (d.nodeId as string) ??
    (d.key as string) ??
    KIND_LABELS[kind]
  );
}

function CanvasInner({ blueprint }: { blueprint: Blueprint }) {
  const wrapper = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition } = useReactFlow();
  const selectedId = useBlueprintStore((s) => s.selectedId);
  const select = useBlueprintStore((s) => s.select);
  const moveNode = useBlueprintStore((s) => s.moveNode);
  const addNode = useBlueprintStore((s) => s.addNode);
  const connect = useBlueprintStore((s) => s.connect);
  const problems = useValidationStore((s) => s.problems);

  const nodes: Node[] = useMemo(
    () =>
      blueprint.nodes.map((n) => ({
        id: n.id,
        type: n.kind === "machine" ? "machine" : "resource",
        position: n.position,
        selected: n.id === selectedId,
        zIndex: n.kind === "machine" ? 0 : 1,
        data: {
          kind: n.kind,
          label: labelOf(n.kind, n.data),
          fact: keyFact(n.kind, n.data),
          problems: problems.filter((p) => p.nodeId === n.id),
          selected: n.id === selectedId,
        } satisfies IvaldiNodeData,
      })),
    [blueprint.nodes, problems, selectedId],
  );

  const edges: Edge[] = useMemo(
    () =>
      blueprint.edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        label: EDGE_LABELS[e.kind],
        animated: false,
        style: { stroke: "var(--color-border)", strokeWidth: 1.2 },
        labelStyle: {
          fill: "var(--color-muted-foreground)",
          fontSize: 9,
          fontFamily: "var(--font-mono)",
          textTransform: "uppercase",
          letterSpacing: "0.12em",
        },
        labelBgStyle: { fill: "var(--color-background)" },
      })),
    [blueprint.edges],
  );

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      for (const change of changes) {
        if (change.type === "position" && change.position && change.dragging === false)
          moveNode(change.id, change.position);
        if (change.type === "position" && change.position && change.dragging)
          moveNode(change.id, change.position);
      }
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
        onNodeClick={(_, node) => select(node.id)}
        onPaneClick={() => select(null)}
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
        <MiniMap
          pannable
          zoomable
          className="!bg-card !border !border-border"
          maskColor="color-mix(in oklab, var(--color-background) 70%, transparent)"
          nodeColor="var(--color-primary)"
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
            Drag a machine from the palette to start the topology.
          </p>
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
