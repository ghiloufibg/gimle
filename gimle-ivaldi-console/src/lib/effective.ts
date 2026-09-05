import type { Blueprint, BlueprintNode, MachineData, TenantData } from "./blueprint";

/**
 * The one place that resolves "which machine is this role on" and "which tenant does this belong
 * to". Both questions have two possible sources -- a link drawn on the canvas, and a field typed
 * into the inspector -- and every screen and every renderer has to answer them the same way, or
 * the design a user sees and the files they export disagree. The link wins: it is a live
 * reference to the other node, so it survives that node being renamed, while the typed field is a
 * copy taken when it was typed.
 */
export interface EffectiveValue {
  /** The value the generated files will carry. */
  value: string;
  /** True when a canvas link supplies it, which makes the typed field advisory. */
  fromEdge: boolean;
}

/** The machine a placed role really lands on: a placedOn link wins over the typed field. */
export function effectiveMachine(bp: Blueprint, node: BlueprintNode): EffectiveValue {
  const edge = bp.edges.find((e) => e.kind === "placedOn" && e.source === node.id);
  const target = edge ? bp.nodes.find((n) => n.id === edge.target) : undefined;
  if (target) return { value: (target.data as MachineData).name, fromEdge: true };
  return { value: (node.data as { machine?: string }).machine ?? "", fromEdge: false };
}

/** The tenant a resource really belongs to: a belongsTo link wins over the typed field. */
export function effectiveTenant(bp: Blueprint, node: BlueprintNode): EffectiveValue {
  const edge = bp.edges.find((e) => e.kind === "belongsTo" && e.source === node.id);
  const target = edge ? bp.nodes.find((n) => n.id === edge.target) : undefined;
  if (target) return { value: (target.data as TenantData).id, fromEdge: true };
  return { value: (node.data as { tenantId?: string }).tenantId ?? "", fromEdge: false };
}

/** The machine name a placed role resolves to, or undefined when it resolves to nothing. */
export function machineNameOf(bp: Blueprint, node: BlueprintNode): string | undefined {
  return effectiveMachine(bp, node).value || undefined;
}

/** The tenant id a resource resolves to, or undefined when it resolves to nothing. */
export function tenantIdOf(bp: Blueprint, node: BlueprintNode): string | undefined {
  return effectiveTenant(bp, node).value || undefined;
}
