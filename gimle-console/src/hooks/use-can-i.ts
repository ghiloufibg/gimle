import { useEffect } from "react";
import { useCanIStore } from "@/stores/useCanIStore";
import type { ResourceKind, Verb } from "@/types";

/**
 * Whether the signed-in operator may perform `verb` on `resource` (optionally scoped to
 * `tenant`), answered by the control plane's own `/authz/can-i` self-subject access review --
 * the same authorization walk a real attempt would be checked against, so a screen can hide or
 * disable a write control before the operator ever submits it instead of only catching the
 * refusal afterward.
 *
 * Returns `undefined` while the answer is still in flight (or hasn't been asked yet); callers
 * should treat that the same as `false` -- disabled until proven allowed, never the reverse.
 */
export function useCanI(resource: ResourceKind, verb: Verb, tenant?: string): boolean | undefined {
  const key = `${resource}:${verb}:${tenant ?? ""}`;
  const result = useCanIStore((s) => s.results[key]);
  const check = useCanIStore((s) => s.check);

  useEffect(() => {
    check(resource, verb, tenant);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return result;
}
