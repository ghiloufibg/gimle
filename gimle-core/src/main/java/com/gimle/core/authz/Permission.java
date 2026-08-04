package com.gimle.core.authz;

import java.util.Optional;

/**
 * Grants {@code verb} on {@code resource}, either cluster-wide ({@code tenantScope} empty) or
 * scoped to one tenant. Not every {@link ResourceKind} is tenant-scopable ({@code NODE}, {@code
 * ROLE}, {@code ROLE_BINDING}, {@code ACCOUNT}, {@code CERTIFICATE_REQUEST}, {@code
 * BOOTSTRAP_TOKEN} never are) -- {@code tenantScope} is simply ignored by the {@code Authorizer}
 * for those, rather than this record enforcing which combinations are legal, matching this
 * codebase's preference for a simple value over a parallel validation hierarchy.
 */
public record Permission(ResourceKind resource, Verb verb, Optional<String> tenantScope) {

  public Permission {
    if (resource == null) {
      throw new IllegalArgumentException("resource must not be null");
    }
    if (verb == null) {
      throw new IllegalArgumentException("verb must not be null");
    }
    if (tenantScope == null) {
      throw new IllegalArgumentException("tenantScope must not be null (use Optional.empty())");
    }
  }

  /** Cluster-wide grant -- the common case for the built-in {@code cluster-admin} role. */
  public static Permission unscoped(ResourceKind resource, Verb verb) {
    return new Permission(resource, verb, Optional.empty());
  }

  /** Grant scoped to exactly one tenant. */
  public static Permission scoped(ResourceKind resource, Verb verb, String tenantId) {
    return new Permission(resource, verb, Optional.of(tenantId));
  }

  /**
   * {@code true} if this permission covers {@code (resource, verb)} for the given request-time
   * {@code requestedTenant} -- unscoped ({@code tenantScope} empty) always matches regardless of
   * {@code requestedTenant}; a scoped permission only matches an identical tenant id.
   */
  public boolean covers(
      ResourceKind requestedResource, Verb requestedVerb, Optional<String> requestedTenant) {
    if (resource != requestedResource || verb != requestedVerb) {
      return false;
    }
    return tenantScope.isEmpty() || tenantScope.equals(requestedTenant);
  }
}
