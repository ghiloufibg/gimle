import { create } from "zustand";

import {
  KIND_LABELS,
  createNode,
  edgeKindFor,
  uid,
  type Blueprint,
  type BlueprintEdge,
  type BlueprintNode,
  type EdgeKind,
  type NodeData,
  type NodeKind,
} from "@/lib/blueprint";
import { normaliseBlueprint } from "@/lib/import";
import { blueprintsRepository } from "@/repositories";
import { ApiError } from "@/repositories/apiClient";

import { useValidationStore } from "./useValidationStore";

const HISTORY_LIMIT = 50;

/**
 * The plain-text node field a placedOn/belongsTo edge's own source node had copied into it at
 * connect time (see `connect` below) -- the field this edge's removal must clear, or it survives
 * as a stale value the moment the edge is gone: editable again, but still naming the machine/
 * tenant the link used to point at. Neither of the other three edge kinds (fronts/allowsCaller/
 * restricts) copies anything into a field at connect time, so there is nothing to clear for them.
 */
function linkedFieldFor(kind: EdgeKind): "machine" | "tenantId" | undefined {
  if (kind === "placedOn") return "machine";
  if (kind === "belongsTo") return "tenantId";
  return undefined;
}

/**
 * Every surviving source node whose own placedOn/belongsTo edge is about to disappear -- named
 * explicitly in doomedEdges, or cascaded because its target is in doomedNodes -- mapped to which
 * field on it needs clearing. A source node itself in doomedNodes needs nothing: it's gone either
 * way. Shared by every deletion path (removeNode, removeNodes, removeEdges,
 * removeNodesAndEdges) so the same rule can't drift between them.
 */
function clearedFieldsFor(
  bp: Blueprint,
  doomedNodes: Set<string>,
  doomedEdges: Set<string>,
): Map<string, "machine" | "tenantId"> {
  const cleared = new Map<string, "machine" | "tenantId">();
  for (const e of bp.edges) {
    if (doomedNodes.has(e.source)) continue;
    const field = linkedFieldFor(e.kind);
    if (field && (doomedEdges.has(e.id) || doomedNodes.has(e.target))) {
      cleared.set(e.source, field);
    }
  }
  return cleared;
}

/**
 * Rendered footprint big enough to detect real visual overlap, not just an identical coordinate --
 * CanvasNodes.tsx's own MachineNode (a fixed 640x260 frame) and ResourceNode (190-230px wide,
 * roughly 90px tall with a full set of label/fact/where lines) sizes.
 */
function footprintFor(kind: NodeKind): { width: number; height: number } {
  return kind === "machine" ? { width: 640, height: 260 } : { width: 230, height: 90 };
}

function overlapsFootprint(
  a: { x: number; y: number },
  aKind: NodeKind,
  b: { x: number; y: number },
  bKind: NodeKind,
): boolean {
  const as = footprintFor(aKind);
  const bs = footprintFor(bKind);
  return (
    a.x < b.x + bs.width && a.x + as.width > b.x && a.y < b.y + bs.height && a.y + as.height > b.y
  );
}

function nextFreePosition(
  nodes: BlueprintNode[],
  requested: { x: number; y: number },
  kind: NodeKind,
): { x: number; y: number } {
  // Sized to the new node's own real footprint (plus a visible gap), not the old fixed 32px --
  // that step was far smaller than a node's actual rendered width, so successive click-to-adds
  // (which all request the same canvas-center point) still landed almost entirely on top of each
  // other even after being nudged clear of an *exact* coordinate match.
  const step = Math.max(footprintFor(kind).width, footprintFor(kind).height) + 24;
  let candidate = requested;
  let guard = 0;
  while (
    nodes.some((n) => overlapsFootprint(candidate, kind, n.position, n.kind)) &&
    guard++ < 200
  ) {
    candidate = { x: candidate.x + step, y: candidate.y + step };
  }
  return candidate;
}

interface BlueprintState {
  blueprint: Blueprint | null;
  selectedId: string | null;
  /** Every node currently selected on the canvas; selectedId is the primary one. */
  selectedIds: string[];
  selectedEdgeIds: string[];
  dirty: boolean;
  past: Blueprint[];
  future: Blueprint[];
  /**
   * A locally-persisted draft found newer than what `load` fetched from the backend -- surfaced
   * so the designer can offer to restore it rather than silently editing on top of stale server
   * state after a tab/process kill inside the debounced-save window lost the in-flight edit.
   */
  recoverableDraft: Blueprint | null;
  restoreDraft: () => void;
  discardDraft: () => void;
  /** Synchronous, immediate localStorage snapshot -- the debounced network save's safety net. */
  persistDraftNow: () => void;
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

function draftStorageKey(id: string): string {
  return `ivaldi:draft:${id}`;
}

/**
 * What actually lands in localStorage: the draft blueprint plus the wall-clock time it was last
 * written, tracked independently of the blueprint's own `updatedAt` -- which only `save` ever
 * stamps. A draft persisted seconds before a crash, with no completed save in between, carries the
 * exact same `updatedAt` as the last successfully-saved server copy; comparing against that field
 * made every such draft look no newer than the server and see it deleted as "already saved," when
 * it was actually the only surviving copy of the edit. `savedAt` is bumped on every local edit
 * instead, so `load` below can compare "when was this draft last touched" against "when was the
 * server copy last saved" -- the question that actually decides whether it's recoverable.
 */
interface DraftEnvelope {
  blueprint: Blueprint;
  savedAt: number;
}

function persistDraft(bp: Blueprint) {
  try {
    const envelope: DraftEnvelope = { blueprint: bp, savedAt: Date.now() };
    localStorage.setItem(draftStorageKey(bp.id), JSON.stringify(envelope));
  } catch {
    // Best-effort only: a private window, cleared site data, or a full quota must never block
    // the in-memory edit or the real debounced save to the backend.
  }
}

function clearDraft(id: string) {
  try {
    localStorage.removeItem(draftStorageKey(id));
  } catch {
    // See persistDraft.
  }
}

function readDraft(id: string): DraftEnvelope | null {
  try {
    const raw = localStorage.getItem(draftStorageKey(id));
    return raw ? (JSON.parse(raw) as DraftEnvelope) : null;
  } catch {
    return null;
  }
}

/**
 * A blueprint fresh off `POST /api/blueprints` carries no `updatedAt` yet -- only `save` ever
 * stamps one. Treating that (or any other unparseable value) as epoch 0 rather than `Invalid Date`
 * is what lets a locally-persisted draft win the comparison below instead of every `Date`
 * comparison against it silently coming back `false`.
 */
function timeOf(iso: string | undefined): number {
  const t = iso ? new Date(iso).getTime() : NaN;
  return Number.isNaN(t) ? 0 : t;
}

/**
 * Runs a blueprint fetched from the backend -- or read back out of a localStorage draft -- through
 * the same defaulting {@link normaliseBlueprint} already gives an imported file, so a document
 * missing an optional field (no `runtime`/`version`/`transport` yet, say a bare {@code
 * POST /api/blueprints} body created outside the console) opens instead of crashing the designer
 * the moment something reads it. `BlueprintStore` documents its own body as opaque JSON it never
 * validates, so this is the one place that gap has to be closed for every reader downstream.
 * Genuinely malformed content (an unknown node kind, a dangling edge) still fails -- that document
 * cannot be opened either way -- so the caller gets it back unchanged rather than losing the error
 * this exists to surface.
 */
function normaliseLoaded<T extends Blueprint | null>(bp: T): T {
  if (!bp) return bp;
  try {
    return normaliseBlueprint(bp) as T;
  } catch {
    return bp;
  }
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

  // Serializes save() calls to at most one PUT in flight at a time: two overlapping calls in this
  // same tab (a debounced autosave still running when the user hits Save, say) would otherwise
  // each send the same expectedUpdatedAt precondition, and whichever lands second would see it as
  // a stale write and be refused with a 409 -- a same-tab race, not the cross-tab conflict that
  // check exists to catch. A call made while one is already in flight waits for it, then saves
  // again only if the blueprint is still dirty by that point.
  let saveInFlight: Promise<void> | null = null;

  const performSave = async (): Promise<"conflict" | void> => {
    const bp = get().blueprint;
    if (!bp) return;
    // The backend never stamps timestamps or versions: we do it here.
    const next: Blueprint = {
      ...bp,
      version: bp.version || "1.0.0",
      updatedAt: new Date().toISOString(),
    };
    try {
      await blueprintsRepository.save(next, bp.updatedAt || undefined);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // Someone else's save landed on this blueprint since this tab last read it (two tabs on
        // the same blueprint, most commonly). Overwriting the server with this tab's own copy, or
        // silently discarding this tab's edits in favor of the server's, both lose someone's work
        // with no sign anything happened -- so neither happens here. This tab's edits become a
        // recoverable draft, the exact dialog a crash-recovered draft already uses, framed as the
        // blueprint having changed elsewhere; the server's current copy replaces what's shown
        // until the user decides.
        persistDraft(bp);
        const server = normaliseLoaded(
          (await blueprintsRepository.get(bp.id).catch(() => undefined)) ?? null,
        );
        if (server) {
          set({ blueprint: server, dirty: false, recoverableDraft: bp });
          revalidate(server);
        } else {
          // Could not even fetch what the server now holds -- leave this tab's own edit as the
          // shown (still dirty, still unsaved) blueprint rather than presenting a "changed
          // elsewhere" view with nothing to show it changed to. It's still offered as a
          // recoverable draft, the same as any other unsaved edit.
          set({ recoverableDraft: bp });
        }
        // Signals coalescedSave below to not treat "still dirty" as more work to save
        // immediately: a conflict needs the user's own restore/discard choice first, not another
        // attempt against the same precondition that just failed.
        return "conflict";
      }
      throw e;
    }
    clearDraft(next.id);
    // Two saves can overlap (a debounced autosave still in flight when the user hits Save, or two
    // autosaves back to back) and resolve out of order. Applying this call's own `next` wholesale
    // would then clobber whatever nodes/edges a later edit -- or a later save's own completion --
    // already committed while this one was in flight. Re-reading the store here and only stamping
    // version/updatedAt onto whatever is current preserves every edit regardless of resolution
    // order; `dirty` only clears if nothing changed since this save's own snapshot was taken.
    set((state) => {
      if (!state.blueprint || state.blueprint.id !== next.id) return {};
      return {
        blueprint: { ...state.blueprint, version: next.version, updatedAt: next.updatedAt },
        dirty: state.blueprint === bp ? false : state.dirty,
      };
    });
  };

  const coalescedSave = (): Promise<void> => {
    if (saveInFlight) return saveInFlight;
    saveInFlight = performSave().then(
      (outcome) => {
        saveInFlight = null;
        // A dirty edit landed while this save was running: it hasn't been sent yet, so run once
        // more rather than leaving it waiting for the next debounce cycle or a further user
        // action. Not after a conflict, though -- that needs the user's own restore/discard
        // choice first, not another attempt against the same precondition that just failed with a
        // 409; and not after a genuine failure (a dropped connection, a 500), which would
        // otherwise turn one bad request into a silent retry storm.
        if (outcome !== "conflict" && get().dirty) void coalescedSave();
      },
      (error: unknown) => {
        saveInFlight = null;
        throw error;
      },
    );
    return saveInFlight;
  };

  return {
    blueprint: null,
    selectedId: null,
    selectedIds: [],
    selectedEdgeIds: [],
    dirty: false,
    past: [],
    future: [],
    recoverableDraft: null,

    load: async (id) => {
      const bp = normaliseLoaded((await blueprintsRepository.get(id)) ?? null);
      const envelope = readDraft(id);
      const draft = envelope ? normaliseLoaded(envelope.blueprint) : null;
      // Recoverable exactly when the draft was last touched after the loaded server copy's own
      // updatedAt -- not when the draft's own (possibly stale, save-only) updatedAt says so.
      const recoverableDraft =
        envelope && draft && (!bp || envelope.savedAt > timeOf(bp.updatedAt)) ? draft : null;
      if (draft && !recoverableDraft) clearDraft(id);
      set({
        blueprint: bp,
        selectedId: null,
        selectedIds: [],
        selectedEdgeIds: [],
        dirty: false,
        past: [],
        future: [],
        recoverableDraft,
      });
      revalidate(bp);
    },

    restoreDraft: () => {
      const draft = get().recoverableDraft;
      const current = get().blueprint;
      if (!draft) return;
      // The draft's own updatedAt is whatever it was at the moment it was captured -- stale by
      // definition once a conflict is what produced it. Applying the draft wholesale carried that
      // stale value right back into `blueprint`, so the very next autosave sent it as the
      // precondition and 409ed again, forever. Only the draft's actual content should overwrite
      // `current` here; `current.updatedAt` (freshly re-fetched during the 409 handling, or
      // otherwise already the best known value) is what the next save must key off.
      const restored = current ? { ...current, ...draft, updatedAt: current.updatedAt } : draft;
      set({ blueprint: restored, dirty: true, recoverableDraft: null, past: [], future: [] });
      revalidate(restored);
    },

    discardDraft: () => {
      const draft = get().recoverableDraft;
      if (draft) clearDraft(draft.id);
      set({ recoverableDraft: null });
    },

    persistDraftNow: () => {
      const bp = get().blueprint;
      if (bp) persistDraft(bp);
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
      const node = createNode(kind, nextFreePosition(bp.nodes, position, kind), seed);
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

    // This one node's own delete button in the Inspector -- the canvas's combined delete gesture
    // goes through removeNodesAndEdges instead, but both can remove a placedOn/belongsTo edge, so
    // both need the same field-clearing treatment (see linkedFieldFor's own comment).
    removeNode: (id) => {
      const bp = get().blueprint;
      if (!bp) return;
      const doomedNodes = new Set([id]);
      const clearedFields = clearedFieldsFor(bp, doomedNodes, new Set());
      commit({
        ...bp,
        nodes: bp.nodes
          .filter((n) => n.id !== id)
          .map((n) => {
            const field = clearedFields.get(n.id);
            return field ? { ...n, data: { ...n.data, [field]: "" } as NodeData } : n;
          }),
        edges: bp.edges.filter((e) => e.source !== id && e.target !== id),
      });
      if (get().selectedId === id) set({ selectedId: null });
      set({ selectedIds: get().selectedIds.filter((n) => n !== id) });
    },

    removeNodes: (ids) => {
      const bp = get().blueprint;
      if (!bp || ids.length === 0) return;
      const doomed = new Set(ids);
      const clearedFields = clearedFieldsFor(bp, doomed, new Set());
      commit({
        ...bp,
        nodes: bp.nodes
          .filter((n) => !doomed.has(n.id))
          .map((n) => {
            const field = clearedFields.get(n.id);
            return field ? { ...n, data: { ...n.data, [field]: "" } as NodeData } : n;
          }),
        edges: bp.edges.filter((e) => !doomed.has(e.source) && !doomed.has(e.target)),
      });
      set({ selectedId: null, selectedIds: [], selectedEdgeIds: [] });
    },

    removeEdges: (ids) => {
      const bp = get().blueprint;
      if (!bp || ids.length === 0) return;
      const doomed = new Set(ids);
      const clearedFields = clearedFieldsFor(bp, new Set(), doomed);
      commit({
        ...bp,
        nodes: bp.nodes.map((n) => {
          const field = clearedFields.get(n.id);
          return field ? { ...n, data: { ...n.data, [field]: "" } as NodeData } : n;
        }),
        edges: bp.edges.filter((e) => !doomed.has(e.id)),
      });
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
      const clearedFields = clearedFieldsFor(bp, doomedNodes, doomedEdges);
      commit({
        ...bp,
        nodes: bp.nodes
          .filter((n) => !doomedNodes.has(n.id))
          .map((n) => {
            const field = clearedFields.get(n.id);
            return field ? { ...n, data: { ...n.data, [field]: "" } as NodeData } : n;
          }),
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
      const edge = bp.edges.find((e) => e.id === edgeId);
      const field = edge && linkedFieldFor(edge.kind);
      const nodes =
        edge && field
          ? bp.nodes.map((n) =>
              n.id === edge.source ? { ...n, data: { ...n.data, [field]: "" } as NodeData } : n,
            )
          : bp.nodes;
      commit({ ...bp, nodes, edges: bp.edges.filter((e) => e.id !== edgeId) });
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

    save: () => coalescedSave(),

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
