package com.gimle.core.authz;

import java.util.Optional;

/**
 * Grants {@code verb} on {@code resource}, either cluster-wide ({@code tenantScope} empty) or
 * scoped to one tenant. Not every {@link ResourceKind} is tenant-scopable ({@code NODE}, {@code
 * ROLE}, {@code ROLE_BINDING}, {@code ACCOUNT}, {@code CERTIFICATE_REQUEST}, {@code
 * BOOTSTRAP_TOKEN} never are) -- {@code tenantScope} is simply ignored by the {@code Authorizer}
 * for those, rather than this record enforcing which combinations are legal, matching this
 * codebase's preference for a simple value over a parallel validation hierarchy.
 *
 * <p>{@code qualifier} is meaningful only alongside {@link ResourceKind#CUSTOM_RESOURCE}, in two
 * forms: a kind name ({@code custom.Greeting}) covers that kind's spec CRUD, and a kind name plus
 * {@code /status} ({@code custom.Greeting/status}) covers <em>only</em> its status writes -- the
 * Kubernetes {@code /status} subresource split, spelled as a qualifier instead of a second
 * resource. Spec-WRITE never implies status-WRITE and vice versa: a human editor can't stomp what
 * an operator reported, and an operator granted only its status qualifier can't alter desired
 * state. An absent qualifier covers every custom kind's spec operations (so pre-existing roles
 * behave sensibly with zero migration) but never a status write, which always takes an explicit
 * {@code /status} grant.
 */
public record Permission(
    ResourceKind resource, Verb verb, Optional<String> tenantScope, Optional<String> qualifier) {

  /** The suffix marking a qualifier as covering only a kind's status sub-document writes. */
  public static final String STATUS_QUALIFIER_SUFFIX = "/status";

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
    if (qualifier == null) {
      throw new IllegalArgumentException("qualifier must not be null (use Optional.empty())");
    }
  }

  /** Cluster-wide grant -- the common case for the built-in {@code cluster-admin} role. */
  public static Permission unscoped(ResourceKind resource, Verb verb) {
    return new Permission(resource, verb, Optional.empty(), Optional.empty());
  }

  /** Grant scoped to exactly one tenant. */
  public static Permission scoped(ResourceKind resource, Verb verb, String tenantId) {
    return new Permission(resource, verb, Optional.of(tenantId), Optional.empty());
  }

  /**
   * {@code true} if this permission covers {@code (resource, verb)} for the given request-time
   * {@code requestedTenant} and {@code requestedQualifier} -- unscoped ({@code tenantScope} empty)
   * always matches regardless of {@code requestedTenant}; a scoped permission only matches an
   * identical tenant id. Qualifier matching: an explicit qualifier matches only an identical
   * request qualifier; an absent one matches any request except a status write (a request qualifier
   * ending in {@value #STATUS_QUALIFIER_SUFFIX}), which only an explicit {@code {kind}/status}
   * grant ever covers.
   */
  public boolean covers(
      ResourceKind requestedResource,
      Verb requestedVerb,
      Optional<String> requestedTenant,
      Optional<String> requestedQualifier) {
    if (resource != requestedResource || verb != requestedVerb) {
      return false;
    }
    if (tenantScope.isPresent() && !tenantScope.equals(requestedTenant)) {
      return false;
    }
    if (qualifier.isPresent()) {
      return qualifier.equals(requestedQualifier);
    }
    return requestedQualifier.filter(q -> q.endsWith(STATUS_QUALIFIER_SUFFIX)).isEmpty();
  }

  /** Qualifier-less convenience overload for the resource kinds that never carry one. */
  public boolean covers(
      ResourceKind requestedResource, Verb requestedVerb, Optional<String> requestedTenant) {
    return covers(requestedResource, requestedVerb, requestedTenant, Optional.empty());
  }
}
