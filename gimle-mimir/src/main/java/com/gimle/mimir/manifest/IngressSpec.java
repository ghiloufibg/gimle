package com.gimle.mimir.manifest;

import com.gimle.core.ingress.IngressRule;
import java.util.List;
import java.util.Optional;

/**
 * A declared set of HTTP routes for the gateway to serve -- the Ingress analogue, and the
 * declarative replacement for hand-authoring {@code gateway.routes} as flat text pushed through
 * {@code /config/*}.
 *
 * <p>The difference that matters is not the format. A config value is opaque to the platform: it is
 * validated only when a gateway happens to parse it, a typo surfaces as a route that silently never
 * matches, and nothing can answer "what routes exist" without reading a string. An {@code Ingress}
 * is validated at admission, listed and RBAC-gated like every other resource, and versioned for
 * compare-and-set the same way {@link NetworkPolicySpec} is.
 *
 * <p>Routes travel to gateways as {@link IngressRule}, not as this record -- {@code gimle-gateway}
 * depends on {@code gimle-core} and never on this package, the same split {@code NetworkPolicyRule}
 * has from {@code NetworkPolicySpec}.
 *
 * <p>{@code tenantId} scopes the routes: a gateway serving a tenant sees that tenant's Ingresses.
 * Two Ingresses in one tenant declaring the same {@code (host, path, prefix)} triple is a conflict
 * the gateway resolves the same way two conflicting config lines already are -- see {@code
 * GatewayDispatcher} -- rather than something admission can catch, since neither submission is
 * wrong on its own and rejecting the second would make the outcome depend on submission order.
 */
public record IngressSpec(String name, String tenantId, List<IngressRule> routes, int version) {

  public IngressSpec {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ingress name must not be blank");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("ingress tenantId must not be blank");
    }
    if (routes == null) {
      throw new IllegalArgumentException("routes must not be null");
    }
    if (routes.isEmpty()) {
      throw new IllegalArgumentException("an ingress must declare at least one route");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative: " + version);
    }
    routes = List.copyOf(routes);
  }

  /** A newly-submitted ingress, before any compare-and-set update has bumped its version. */
  public IngressSpec(String name, String tenantId, List<IngressRule> routes) {
    this(name, tenantId, routes, 0);
  }

  /** The same ingress at the next version, for a compare-and-set update. */
  public IngressSpec withVersion(int nextVersion) {
    return new IngressSpec(name, tenantId, routes, nextVersion);
  }

  /** Whether any declared route is constrained to {@code host}, or matches every host. */
  public boolean matchesHost(Optional<String> host) {
    return routes.stream().anyMatch(route -> route.host().isEmpty() || route.host().equals(host));
  }
}
