import { create } from "zustand";

import {
  KIND_LABELS,
  createNode,
  edgeKindFor,
  uid,
  type Blueprint,
  type BlueprintEdge,
  type BlueprintNode,
  type NodeData,
  type NodeKind,
} from "@/lib/blueprint";
import { blueprintsRepository } from "@/repositories";

import { useValidationStore } from "./useValidationStore";

const HISTORY_LIMIT = 50;

interface BlueprintState {
  blueprint: Blueprint | null;
  selectedId: string | null;
  /** Every node currently selected on the canvas; selectedId is the primary one. */
  selectedIds: string[];
  selectedEdgeIds: string[];
  dirty: boolean;
  past: Blueprint[];
  future: Blueprint[];
  load: (id: string) => Promise<void>;
  select: (id: string | null) => void;
  setSelection: (nodeIds: string[], edgeIds: string[]) => void;
  addNode: (kind: NodeKind, position: { x: number; y: number }) => BlueprintNode | null;
  updateNode: (id: string, patch: Partial<NodeData>) => void;
  removeNode: (id: string) => void;
  removeNodes: (ids: string[]) => void;
  removeEdges: (ids: string[]) => void;
  /** One node deletion is one link removal too, wherever it has any -- see `removeNodesAndEdges`. */
  removeNodesAndEdges: (nodeIds: string[], edgeIds: string[]) => void;
  moveNode: (id: string, position: { x: number; y: number }) => void;
  /**
   * Snapshots the blueprint before a drag starts, and stamps it as one undo step once the drag
   * ends -- `moveNode` itself never touches history, so every intermediate position during the
   * drag stays a plain, un-undoable state update, and only the drag's net result is checkpointed.
   */
  beginDrag: () => void;
  endDrag: () => void;
  connect: (source: string, target: string) => { ok: boolean; reason?: string };
  disconnect: (edgeId: string) => void;
  patchBlueprint: (patch: Partial<Blueprint>) => void;
  setBlueprint: (blueprint: Blueprint) => void;
  save: () => Promise<void>;
  duplicate: () => Promise<Blueprint | null>;
  undo: () => void;
  redo: () => void;
}

/** "An Agent" / "A Store", using the palette's own labels. */
function article(label: string): string {
  return `${/^[aeiou]/i.test(label) ? "An" : "A"} ${label}`;
}

/** The same, mid-sentence: the article lowercases, the kind's own label does not. */
function lowerArticle(label: string): string {
  return `${/^[aeiou]/i.test(label) ? "an" : "a"} ${label}`;
}

function revalidate(bp: Blueprint | null) {
  useValidationStore.getState().recompute(bp);
}

export const useBlueprintStore = create<BlueprintState>((set, get) => {
  const commit = (next: Blueprint, markDirty = true) => {
    const current = get().blueprint;
    const past = current ? [...get().past, current].slice(-HISTORY_LIMIT) : get().past;
    set({ blueprint: next, past, future: [], dirty: markDirty ? true : get().dirty });
    revalidate(next);
  };

  // The blueprint as of the start of the drag in progress, if any -- module-local rather than
  // store state, since it drives no rendering of its own and only ever needs to be read back by
  // endDrag once, right after it is written by beginDrag.
  let dragSnapshot: Blueprint | null = null;

  return {
    blueprint: null,
    selectedId: null,
    selectedIds: [],
    selectedEdgeIds: [],
    dirty: false,
    past: [],
    future: [],

    load: async (id) => {
      const bp = (await blueprintsRepository.get(id)) ?? null;
      set({
        blueprint: bp,
        selectedId: null,
        selectedIds: [],
        selectedEdgeIds: [],
        dirty: false,
        past: [],
        future: [],
      });
      revalidate(bp);
    },

    select: (id) => set({ selectedId: id, selectedIds: id ? [id] : [], selectedEdgeIds: [] }),

    setSelection: (nodeIds, edgeIds) => {
      const state = get();
      const same = (a: string[], b: string[]) =>
        a.length === b.length && a.every((v, i) => v === b[i]);
      // React Flow reports the selection on every render: only a real change
      // may touch the store, otherwise the canvas loops.
      if (same(state.selectedIds, nodeIds) && same(state.selectedEdgeIds, edgeIds)) return;
      set({
        selectedIds: nodeIds,
        selectedEdgeIds: edgeIds,
        selectedId: nodeIds.length === 1 ? nodeIds[0] : null,
      });
    },

    addNode: (kind, position) => {
      const bp = get().blueprint;
      if (!bp) return null;
      const seed = bp.nodes.filter((n) => n.kind === kind).length + 1;
      const node = createNode(kind, position, seed);
      const edges: BlueprintEdge[] = [];
      const machines = bp.nodes.filter((n) => n.kind === "machine");
      const tenants = bp.nodes.filter((n) => n.kind === "tenant");
      const kindOfEdge = (target: BlueprintNode) => edgeKindFor(kind, target.kind);
      if (machines.length === 1 && kindOfEdge(machines[0]) === "placedOn") {
        edges.push({ id: uid("edge"), kind: "placedOn", source: node.id, target: machines[0].id });
        (node.data as { machine?: string }).machine = (machines[0].data as { name: string }).name;
      }
      if (tenants.length === 1 && kindOfEdge(tenants[0]) === "belongsTo") {
        edges.push({ id: uid("edge"), kind: "belongsTo", source: node.id, target: tenants[0].id });
        (node.data as { tenantId?: string }).tenantId = (tenants[0].data as { id: string }).id;
      }
      commit({ ...bp, nodes: [...bp.nodes, node], edges: [...bp.edges, ...edges] });
      set({ selectedId: node.id, selectedIds: [node.id], selectedEdgeIds: [] });
      return node;
    },

    updateNode: (id, patch) => {
      const bp = get().blueprint;
      if (!bp) return;
      commit({
        ...bp,
        nodes: bp.nodes.map((n) =>
          n.id === id ? { ...n, data: { ...n.data, ...patch } as NodeData } : n,
        ),
      });
    },

    removeNode: (id) => {
      const bp = get().blueprint;
      if (!bp) return;
      commit({
        ...bp,
        nodes: bp.nodes.filter((n) => n.id !== id),
        edges: bp.edges.filter((e) => e.source !== id && e.target !== id),
      });
      if (get().selectedId === id) set({ selectedId: null });
      set({ selectedIds: get().selectedIds.filter((n) => n !== id) });
    },

    removeNodes: (ids) => {
      const bp = get().blueprint;
      if (!bp || ids.length === 0) return;
      const doomed = new Set(ids);
      commit({
        ...bp,
        nodes: bp.nodes.filter((n) => !doomed.has(n.id)),
        edges: bp.edges.filter((e) => !doomed.has(e.source) && !doomed.has(e.target)),
      });
      set({ selectedId: null, selectedIds: [], selectedEdgeIds: [] });
    },

    removeEdges: (ids) => {
      const bp = get().blueprint;
      if (!bp || ids.length === 0) return;
      const doomed = new Set(ids);
      commit({ ...bp, edges: bp.edges.filter((e) => !doomed.has(e.id)) });
      set({ selectedEdgeIds: [] });
    },

    /**
     * Removing a node with its own links used to cost two undo steps for one user action: React
     * Flow reports a delete gesture as a node removal AND a separate edge removal (the connected
     * edges, cascaded), and each landed as its own commit -- so restoring "the node and its links"
     * needed Ctrl+Z twice, despite the confirmation dialog's own promise of one. Both sides of one
     * gesture now land in a single commit.
     */
    removeNodesAndEdges: (nodeIds, edgeIds) => {
      const bp = get().blueprint;
      if (!bp || (nodeIds.length === 0 && edgeIds.length === 0)) return;
      const doomedNodes = new Set(nodeIds);
      const doomedEdges = new Set(edgeIds);
      commit({
        ...bp,
        nodes: bp.nodes.filter((n) => !doomedNodes.has(n.id)),
        edges: bp.edges.filter(
          (e) => !doomedEdges.has(e.id) && !doomedNodes.has(e.source) && !doomedNodes.has(e.target),
        ),
      });
      set({
        selectedId: null,
        selectedIds: get().selectedIds.filter((id) => !doomedNodes.has(id)),
        selectedEdgeIds: get().selectedEdgeIds.filter((id) => !doomedEdges.has(id)),
      });
    },

    moveNode: (id, position) => {
      const bp = get().blueprint;
      if (!bp) return;
      set({
        blueprint: {
          ...bp,
          nodes: bp.nodes.map((n) => (n.id === id ? { ...n, position } : n)),
        },
        dirty: true,
      });
    },

    beginDrag: () => {
      dragSnapshot = get().blueprint;
    },

    endDrag: () => {
      const before = dragSnapshot;
      dragSnapshot = null;
      const current = get().blueprint;
      // moveNode replaces `blueprint` with a new object on every position update, so identity
      // alone tells whether the drag actually moved anything: no move at all (a click that never
      // became a drag) means nothing to undo.
      if (!before || !current || before === current) return;
      set({ past: [...get().past, before].slice(-HISTORY_LIMIT), future: [] });
    },

    connect: (source, target) => {
      const bp = get().blueprint;
      if (!bp) return { ok: false, reason: "No blueprint loaded." };
      const s = bp.nodes.find((n) => n.id === source);
      const t = bp.nodes.find((n) => n.id === target);
      if (!s || !t) return { ok: false, reason: "Unknown node." };
      const kind = edgeKindFor(s.kind, t.kind);
      if (!kind)
        return {
          ok: false,
          // Only the article is lowercased: lowercasing the whole label destroyed the kind's own
          // spelling, so one sentence read "A DaemonSet cannot connect to a daemonset."
          reason: `${article(KIND_LABELS[s.kind])} cannot connect to ${lowerArticle(KIND_LABELS[t.kind])}.`,
        };
      if (bp.edges.some((e) => e.source === source && e.target === target && e.kind === kind))
        return { ok: false, reason: "That link already exists." };
      const edge: BlueprintEdge = { id: uid("edge"), kind, source, target };
      const nodes = bp.nodes.map((n) => {
        if (n.id !== source) return n;
        if (kind === "placedOn")
          return {
            ...n,
            data: { ...n.data, machine: (t.data as { name: string }).name } as NodeData,
          };
        if (kind === "belongsTo")
          return { ...n, data: { ...n.data, tenantId: (t.data as { id: string }).id } as NodeData };
        return n;
      });
      commit({ ...bp, nodes, edges: [...bp.edges, edge] });
      return { ok: true };
    },

    disconnect: (edgeId) => {
      const bp = get().blueprint;
      if (!bp) return;
      commit({ ...bp, edges: bp.edges.filter((e) => e.id !== edgeId) });
    },

    patchBlueprint: (patch) => {
      const bp = get().blueprint;
      if (!bp) return;
      commit({ ...bp, ...patch });
    },

    setBlueprint: (blueprint) => {
      set({
        blueprint,
        selectedId: null,
        selectedIds: [],
        selectedEdgeIds: [],
        dirty: true,
        past: [],
        future: [],
      });
      revalidate(blueprint);
    },

    save: async () => {
      const bp = get().blueprint;
      if (!bp) return;
      // The backend never stamps timestamps or versions: we do it here.
      const next: Blueprint = {
        ...bp,
        version: bp.version || "1.0.0",
        updatedAt: new Date().toISOString(),
      };
      await blueprintsRepository.save(next);
      set({ blueprint: next, dirty: false });
    },

    duplicate: async () => {
      const bp = get().blueprint;
      if (!bp) return null;
      const copy: Blueprint = {
        ...bp,
        name: `${bp.name}-copy`,
        version: bp.version || "1.0.0",
        updatedAt: new Date().toISOString(),
      };
      const created = await blueprintsRepository.create(copy);
      return { ...copy, id: created.id };
    },

    undo: () => {
      const { past, blueprint, future } = get();
      if (!past.length || !blueprint) return;
      const previous = past[past.length - 1];
      set({
        blueprint: previous,
        past: past.slice(0, -1),
        future: [blueprint, ...future].slice(0, HISTORY_LIMIT),
        dirty: true,
      });
      revalidate(previous);
    },

    redo: () => {
      const { future, blueprint, past } = get();
      if (!future.length || !blueprint) return;
      const next = future[0];
      set({
        blueprint: next,
        future: future.slice(1),
        past: [...past, blueprint].slice(-HISTORY_LIMIT),
        dirty: true,
      });
      revalidate(next);
    },
  };
});
