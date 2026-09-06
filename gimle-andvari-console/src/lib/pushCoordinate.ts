import type { ModuleCoordinate } from "@/lib/moduleDescriptor";

/** The push dialog's two coordinate fields, plus the jar they were derived from if any. */
export interface CoordinateFields extends ModuleCoordinate {
  /** The jar whose own descriptor supplied these values, or null when they were typed. */
  derivedFrom: string | null;
}

/**
 * What the coordinate fields hold the moment a different jar is picked, before that jar's own
 * descriptor has been read. A coordinate the previous jar declared was never the operator's input,
 * so it is cleared rather than carried over onto a different jar -- once the fields unlock for a
 * descriptor-less jar, values left sitting in them read as confirmed when they are simply stale.
 * A hand-typed coordinate survives: that one the operator did enter.
 */
export function coordinateForPickedFile(
  current: CoordinateFields,
  defaultModuleId: string,
): ModuleCoordinate {
  return current.derivedFrom === null
    ? { moduleId: current.moduleId, version: current.version }
    : { moduleId: defaultModuleId, version: "" };
}
