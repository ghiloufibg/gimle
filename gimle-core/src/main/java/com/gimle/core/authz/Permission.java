package com.gimle.core.authz;

import java.util.Locale;
import java.util.Optional;

/**
 * Grants {@code verb} on {@code resource}, either cluster-wide ({@code tenantScope} empty) or
 * scoped to one tenant. Not every {@link ResourceKind} is tenant-scopable ({@code NODE}, {@code
 * ROLE}, {@code ROLE_BINDING}, {@code ACCOUNT}, {@code CERTIFICATE_REQUEST}, {@code
 * BOOTSTRAP_TOKEN} never are) -- {@code tenantScope} is simply ignored by the {@code Authorizer}
 * for those, rather than this record enforcing which combinations are legal, matching this
 * codebase's preference for a simple value over a parallel validation hierarchy.
 *
 * <p>Each of the three positions may be left empty, the wildcard: an empty {@code resource} covers
 * every {@link ResourceKind}, an empty {@code verb} every {@link Verb}, an empty {@code
 * tenantScope} every tenant. A wildcard is stored as a wildcard and widened only when a request is
 * matched against it in {@link #covers} -- never expanded into an enumerated permission set when
 * the role is written. That is what lets a role granting every resource kind automatically cover a
 * {@link ResourceKind} added to the enum later, with the stored role untouched, and it is why an
 * operator needing a role broader than the per-tenant templates but narrower than {@code
 * cluster-admin} does not have to hand-enumerate (and then re-edit) every resource-by-verb
 * combination. {@value #ALL} is the wildcard's spelling everywhere it is written down: on the wire,
 * in a manifest, and on the CLI's own {@code --permission} flag.
 *
 * <p>{@code qualifier} is meaningful only alongside {@link ResourceKind#CUSTOM_RESOURCE}, in two
 * forms: a kind name ({@code custom.Greeting}) covers that kind's spec CRUD, and a kind name plus
 * {@code /status} ({@code custom.Greeting/status}) covers <em>only</em> its status writes -- the
 * Kubernetes {@code /status} subresource split, spelled as a qualifier instead of a second
 * resource. Spec-WRITE never implies status-WRITE and vice versa: a human editor can't stomp what
 * an operator reported, and an operator granted only its status qualifier can't alter desired
 * state. An absent qualifier covers every custom kind's spec operations (so pre-existing roles
 * behave sensibly with zero migration) but never a status write, which always takes an explicit
 * {@code {kind}/status} grant.
 */
public record Permission(
    Optional<ResourceKind> resource,
    Optional<Verb> verb,
    Optional<String> tenantScope,
    Optional<String> qualifier) {

  /** The wildcard spelling accepted in the resource, verb, and tenant-scope positions. */
  public static final String ALL = "*";

  /** The suffix marking a qualifier as covering only a kind's status sub-document writes. */
  public static final String STATUS_QUALIFIER_SUFFIX = "/status";

  public Permission {
    if (resource == null) {
      throw new IllegalArgumentException(
          "resource must not be null (use Optional.empty() for every kind)");
    }
    if (verb == null) {
      throw new IllegalArgumentException(
          "verb must not be null (use Optional.empty() for every verb)");
    }
    if (tenantScope == null) {
      throw new IllegalArgumentException("tenantScope must not be null (use Optional.empty())");
    }
    if (qualifier == null) {
      throw new IllegalArgumentException("qualifier must not be null (use Optional.empty())");
    }
  }

  /** The named-kind, named-verb form -- every grant that isn't a wildcard in either position. */
  public Permission(
      ResourceKind resource, Verb verb, Optional<String> tenantScope, Optional<String> qualifier) {
    this(Optional.of(resource), Optional.of(verb), tenantScope, qualifier);
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
   * {@code requestedTenant} and {@code requestedQualifier} -- an empty {@code resource}/{@code
   * verb}/{@code tenantScope} is the wildcard and matches whatever the request asked for; a named
   * one only an identical value. Qualifier matching: an explicit qualifier matches only an
   * identical request qualifier; an absent one matches any request except a status write (a request
   * qualifier ending in {@value #STATUS_QUALIFIER_SUFFIX}), which only an explicit {@code
   * {kind}/status} grant ever covers -- including under a wildcard resource grant, since "every
   * resource kind" is a breadth statement about kinds, not a licence to overwrite what an operator
   * reported.
   */
  public boolean covers(
      ResourceKind requestedResource,
      Verb requestedVerb,
      Optional<String> requestedTenant,
      Optional<String> requestedQualifier) {
    if (!coversResource(requestedResource) || !coversVerb(requestedVerb)) {
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

  /**
   * Whether this permission's resource position admits {@code requestedResource} at all, ignoring
   * every other position. Exists so a caller answering a broader question than a single request --
   * "does this principal hold any read grant for this kind" -- widens the wildcard identically to
   * {@link #covers} instead of comparing {@link #resource()} itself and silently missing it.
   */
  public boolean coversResource(ResourceKind requestedResource) {
    return resource.isEmpty() || resource.get() == requestedResource;
  }

  /** The verb-position counterpart of {@link #coversResource}. */
  public boolean coversVerb(Verb requestedVerb) {
    return verb.isEmpty() || verb.get() == requestedVerb;
  }

  /** This permission's resource position as it is written down: a kind name, or {@value #ALL}. */
  public String resourceToken() {
    return resource.map(ResourceKind::name).orElse(ALL);
  }

  /** This permission's verb position as it is written down: a verb name, or {@value #ALL}. */
  public String verbToken() {
    return verb.map(Verb::name).orElse(ALL);
  }

  /** Reads a resource position back, {@value #ALL} yielding the wildcard. */
  public static Optional<ResourceKind> parseResource(String token) {
    String normalized = requireToken(token, "resource");
    if (ALL.equals(normalized)) {
      return Optional.empty();
    }
    try {
      return Optional.of(ResourceKind.valueOf(normalized));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "unknown permission resource '" + token + "' (expected a resource kind or " + ALL + ")");
    }
  }

  /** Reads a verb position back, {@value #ALL} yielding the wildcard. */
  public static Optional<Verb> parseVerb(String token) {
    String normalized = requireToken(token, "verb");
    if (ALL.equals(normalized)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Verb.valueOf(normalized));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "unknown permission verb '" + token + "' (expected a verb or " + ALL + ")");
    }
  }

  /**
   * Reads a tenant-scope position back. {@code null}, blank, and {@value #ALL} all yield the
   * wildcard: an omitted scope has always meant "every tenant", and {@value #ALL} is the explicit
   * spelling of that same grant, so the three positions read the same way.
   */
  public static Optional<String> parseTenantScope(String token) {
    if (token == null || token.isBlank() || ALL.equals(token.trim())) {
      return Optional.empty();
    }
    return Optional.of(token);
  }

  /**
   * Reads a qualifier position back. Deliberately no wildcard here: "every custom kind's specs" is
   * already what an absent qualifier means, and "every kind including its status writes" is not
   * expressible on purpose, since a status grant is exactly the authority a spec grant must never
   * imply. A bare {@value #ALL} is rejected rather than stored as a grant that would match nothing.
   */
  public static Optional<String> parseQualifier(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    if (ALL.equals(token.trim())) {
      throw new IllegalArgumentException(
          "permission qualifier must not be "
              + ALL
              + " (omit it to cover every custom kind's specs, or name one kind, e.g."
              + " custom.Greeting or custom.Greeting"
              + STATUS_QUALIFIER_SUFFIX
              + ")");
    }
    return Optional.of(token);
  }

  private static String requireToken(String token, String position) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException(
          "permission "
              + position
              + " must not be blank (use "
              + ALL
              + " for every "
              + position
              + ")");
    }
    return token.trim().toUpperCase(Locale.ROOT);
  }
}
