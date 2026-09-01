/**
 * Whether an inline create/edit form on a list screen is holding work the operator has not saved
 * yet -- either it is editing an existing row, or something has been typed into it.
 *
 * Screens use this to suspend auto-refresh while it is true. A re-read cannot overwrite React's
 * own form state directly, but it can pull the row being edited out from under the form, or
 * reorder the list around it mid-keystroke; neither is something an operator asked for.
 */
export function hasUnsavedInput(
  editing: string | null,
  name: string,
  form: Record<string, string>,
): boolean {
  if (editing !== null) return true;
  if (name.trim() !== "") return true;
  return Object.values(form).some((value) => value.trim() !== "");
}
