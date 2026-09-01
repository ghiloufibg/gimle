/**
 * Reconciles the permission vocabulary the control plane serves with the bundled fallback copy.
 *
 * The console cannot hardcode the set of grantable resource kinds without going stale the next time
 * the platform grows one -- which is why the picker reads `GET /authz/vocabulary` instead. These
 * helpers decide what the picker actually offers once that answer (or the lack of one) is in.
 */

/**
 * The list to offer: whatever the control plane served, or the bundled fallback when it served
 * nothing usable. A server that answers with an empty list is treated as unreachable rather than as
 * "no kinds exist" -- an empty picker can only ever produce an unauthorable role.
 */
export function vocabularyOptions(served: string[] | null, fallback: readonly string[]): string[] {
  if (served === null || served.length === 0) return [...fallback];
  return [...served];
}

/**
 * The same list, guaranteed to contain `selected`. A role written against an older or newer control
 * plane can name a kind this build's vocabulary doesn't list; appending it keeps the row showing
 * what it actually grants instead of rendering blank and silently rewriting it on the next save.
 */
export function optionsIncludingSelected(options: string[], selected: string): string[] {
  return options.includes(selected) ? options : [...options, selected];
}
