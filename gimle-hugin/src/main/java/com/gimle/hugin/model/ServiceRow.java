package com.gimle.hugin.model;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One Service as the services view draws it: the fields of {@code GET /services} that have a
 * column, plus the size of the endpoint set {@code GET /services/{name}/endpoints} currently
 * resolves for it.
 *
 * <p>{@code endpointCount} empty means the endpoint read failed or came back without an {@code
 * endpoints} array at all -- deliberately a different thing from a count of zero, which is the real
 * misconfiguration this view exists to surface. Collapsing the two would make a Service nobody
 * could ask about look identical to one whose backing instances have all gone.
 *
 * <p>Nothing here checks a port range or a protocol spelling. This row is whatever the control
 * plane actually served, so that a response this build cannot make sense of costs the one column it
 * feeds rather than dropping the Service out of the table entirely.
 */
public record ServiceRow(
    String name,
    Optional<String> tenantId,
    List<String> deploymentNames,
    int port,
    OptionalInt targetPort,
    Optional<String> externalName,
    String protocol,
    OptionalInt endpointCount) {

  public ServiceRow {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (tenantId == null || externalName == null) {
      throw new IllegalArgumentException("optional fields must not be null; use Optional.empty()");
    }
    if (targetPort == null || endpointCount == null) {
      throw new IllegalArgumentException(
          "optional ports must not be null; use OptionalInt.empty()");
    }
    if (protocol == null || protocol.isBlank()) {
      throw new IllegalArgumentException("protocol must not be blank");
    }
    deploymentNames = List.copyOf(deploymentNames);
  }

  /** The single word the STATE column shows. */
  public String state() {
    if (endpointCount.isEmpty()) {
      return "UNKNOWN";
    }
    return endpointCount.getAsInt() == 0 ? "NO ENDPOINTS" : "READY";
  }

  /**
   * A Service that resolves to nothing: declared, addressable, and with no live instance behind it
   * for a call to land on. Unknown is not unresolved -- a count nobody could read says nothing
   * about the Service.
   */
  public boolean unresolved() {
    return endpointCount.isPresent() && endpointCount.getAsInt() == 0;
  }

  /** Whether this is the ExternalName shape: an alias for a host outside the cluster. */
  public boolean external() {
    return externalName.isPresent();
  }
}
