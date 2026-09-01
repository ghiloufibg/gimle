import { checkRetireTarget as checkKeyRetireTarget } from "@/lib/key-retirement";
import type { RetireTargetCheck } from "@/lib/key-retirement";

export { retirementConfirmed } from "@/lib/key-retirement";
export type { RetireTargetCheck } from "@/lib/key-retirement";

/** How the shared retirement gate names Fafnir's symmetric ring in every rejection it reports. */
const KEY_NOUN = "secrets master key";

/**
 * The Secrets screen's binding of the shared retire-target pre-flight the Seal Keys screen also
 * uses -- both rings enforce the identical rules server-side and both retirements are equally
 * irreversible, so both screens gate them the same way.
 */
export function checkRetireTarget(raw: string, activeKeyId: number | null): RetireTargetCheck {
  return checkKeyRetireTarget(raw, activeKeyId, KEY_NOUN);
}
