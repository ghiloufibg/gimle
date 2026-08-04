package com.gimle.core.authz;

import java.util.Set;

/** A named, reusable set of {@link Permission}s -- bound to a subject via {@link RoleBinding}. */
public record Role(String name, Set<Permission> permissions) {

  public Role {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (permissions == null) {
      throw new IllegalArgumentException("permissions must not be null");
    }
    permissions = Set.copyOf(permissions);
  }
}
