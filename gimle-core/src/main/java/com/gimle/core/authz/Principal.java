package com.gimle.core.authz;

import java.util.Set;

/**
 * A caller's identity, resolved either from a verified mTLS client certificate's {@code CN=}/{@code
 * O=} RDNs (the CLI, node agents, control-plane peers) or from a verified console session cookie
 * ({@code name} is the {@link Account} username, {@code groups} always empty -- a session identity
 * relies purely on a direct {@code "user:<name>"} {@link RoleBinding}, never a group binding, since
 * there is no certificate {@code O=} to derive a group from). Deliberately carries no field
 * recording which of the two it came from -- {@link com.gimle.core.authz} downstream of this point
 * (permission resolution) must not care.
 */
public record Principal(String name, Set<String> groups) {

  public Principal {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (groups == null) {
      throw new IllegalArgumentException("groups must not be null");
    }
    groups = Set.copyOf(groups);
  }
}
