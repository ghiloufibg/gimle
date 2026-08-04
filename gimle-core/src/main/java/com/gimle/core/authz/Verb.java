package com.gimle.core.authz;

/**
 * Deliberately coarser than HTTP methods or Kubernetes RBAC's get/list/watch/create/update/patch/
 * delete matrix: {@link #READ} covers GET/LIST, {@link #WRITE} covers POST/PUT (create-or-apply,
 * matching how {@code apply}/{@code set} already conflate create-and-update elsewhere in this
 * codebase), {@link #DELETE} its own verb, {@link #APPROVE} the one genuinely distinct action
 * ({@code /bootstrap/csr/{id}/approve}) that isn't well described as read/write/delete of anything.
 * Four verbs are enough to express every route {@code ApiServer} actually has -- there is no watch
 * API, no PATCH distinct from a full-replace {@code apply}, so a full CRUD-plus-custom matrix would
 * model capabilities the platform doesn't have.
 */
public enum Verb {
  READ,
  WRITE,
  DELETE,
  APPROVE
}
