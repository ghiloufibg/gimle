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
  const epoch = useCanIStore((s) => s.epoch);

  // `epoch` is a dependency, not just `key`: a cache clear (a real principal change, or a
  // transient 401 from an unrelated in-flight request) wipes `results` without this control's own
  // key ever changing, so re-asking has to be driven by the clear itself, not by anything this
  // hook's own caller controls.
  useEffect(() => {
    check(resource, verb, tenant);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, epoch]);

  return result;
}
