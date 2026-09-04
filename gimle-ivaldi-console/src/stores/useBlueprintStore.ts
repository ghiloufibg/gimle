import { create } from "zustand";

import {
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
  dirty: boolean;
  past: Blueprint[];
  future: Blueprint[];
  load: (id: string) => Promise<void>;
  select: (id: string | null) => void;
  addNode: (kind: NodeKind, position: { x: number; y: number }) => BlueprintNode | null;
  updateNode: (id: string, patch: Partial<NodeData>) => void;
  removeNode: (id: string) => void;
  moveNode: (id: string, position: { x: number; y: number }) => void;
  connect: (source: string, target: string) => { ok: boolean; reason?: string };
  disconnect: (edgeId: string) => void;
  patchBlueprint: (patch: Partial<Blueprint>) => void;
  setBlueprint: (blueprint: Blueprint) => void;
  save: () => Promise<void>;
  duplicate: () => Promise<Blueprint | null>;
  undo: () => void;
  redo: () => void;
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

  return {
    blueprint: null,
    selectedId: null,
    dirty: false,
    past: [],
    future: [],

    load: async (id) => {
      const bp = (await blueprintsRepository.get(id)) ?? null;
      set({ blueprint: bp, selectedId: null, dirty: false, past: [], future: [] });
      revalidate(bp);
    },

    select: (id) => set({ selectedId: id }),

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
      set({ selectedId: node.id });
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

    connect: (source, target) => {
      const bp = get().blueprint;
      if (!bp) return { ok: false, reason: "No blueprint loaded." };
      const s = bp.nodes.find((n) => n.id === source);
      const t = bp.nodes.find((n) => n.id === target);
      if (!s || !t) return { ok: false, reason: "Unknown node." };
      const kind = edgeKindFor(s.kind, t.kind);
      if (!kind) return { ok: false, reason: `A ${s.kind} cannot connect to a ${t.kind}.` };
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
      set({ blueprint, selectedId: null, dirty: true, past: [], future: [] });
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
