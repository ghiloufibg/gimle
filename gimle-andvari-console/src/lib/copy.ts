/**
 * A copy control's accessible name. It names what activating the control will do -- naming it
 * after the outcome instead tells a screen-reader user, before they have ever activated it, that
 * the value is already on their clipboard.
 */
export function copyActionLabel(subject: string): string {
  return `Copy ${subject}`;
}

/** What a copy that has actually happened is reported as, both in the toast and on the control. */
export function copiedMessage(subject: string): string {
  return `${subject} copied`;
}
