/**
 * The shared pre-flight and confirmation gate behind every key-ring retirement this console can
 * trigger -- Fafnir's asymmetric sealing ring and its symmetric secrets master ring alike. Both
 * rings enforce the identical rules server-side, and both retirements are permanently destructive,
 * so they are checked and confirmed identically here rather than each screen growing its own
 * near-miss variant of the same dialog.
 */

/** The lowest key id on either ring, always regenerated if absent, so retiring it is refused. */
const BASE_KEY_ID = 0;

/** A key id travels the wire as a single unsigned byte on both rings. */
const MAX_KEY_ID = 255;

export interface RetireTargetCheck {
  /** The id to send, or null when `error` explains why nothing may be sent. */
  keyId: number | null;
  error: string | null;
}

/**
 * Mirrors every rejection Fafnir's own retire path makes, so a hopeless id is refused here with a
 * specific reason rather than round-tripping to a bare 400. `keyNoun` names the ring in each
 * message ("sealing key", "secrets master key"). The active-key rule is the one that needs the
 * currently-loaded key: it cannot be checked at all before that id is known, so an unknown active
 * id deliberately lets the id through for the server to rule on.
 */
export function checkRetireTarget(
  raw: string,
  activeKeyId: number | null,
  keyNoun: string,
): RetireTargetCheck {
  const trimmed = raw.trim();
  if (trimmed === "") {
    return { keyId: null, error: `Enter the id of the ${keyNoun} to retire.` };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { keyId: null, error: `A ${keyNoun} id is a whole number.` };
  }
  const keyId = Number(trimmed);
  if (keyId > MAX_KEY_ID) {
    return { keyId: null, error: `A ${keyNoun} id is between 0 and ${MAX_KEY_ID}.` };
  }
  if (keyId === BASE_KEY_ID) {
    return {
      keyId: null,
      error: `Key 0 is the base ${keyNoun} and can never be retired — it would regenerate.`,
    };
  }
  if (activeKeyId !== null && keyId === activeKeyId) {
    return {
      keyId: null,
      error: `Key ${keyId} is the active ${keyNoun}. Rotate first, then retire it.`,
    };
  }
  return { keyId, error: null };
}

/**
 * The typed-confirmation gate on retirement: an operator has to write the id out again, so the
 * destructive action can never ride on one mis-aimed click the way rotation safely can.
 */
export function retirementConfirmed(typed: string, keyId: number): boolean {
  return typed.trim() === String(keyId);
}
