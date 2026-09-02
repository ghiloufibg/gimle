package com.gimle.core.ingress;

import java.util.Optional;

/**
 * One HTTP route an Ingress declares, in the shape that travels from the control plane to a gateway
 * -- the Ingress analogue of {@code NetworkPolicyRule}, and separate from {@code IngressSpec} for
 * the identical reason that rule is separate from {@code NetworkPolicySpec}: {@code gimle-gateway}
 * depends on {@code gimle-core}, never on {@code gimle-mimir}'s manifest types, so the manifest
 * record cannot be the wire record.
 *
 * <p>{@code kind} names which of the gateway's three route shapes this becomes, and the target
 * fields are read per kind rather than modelled as a sealed hierarchy: a flat record survives a
 * JSON round trip with no discriminator handling on either side, and the gateway already owns the
 * typed {@code GatewayRoute} hierarchy this converts into. A field irrelevant to a kind is simply
 * absent -- {@code serviceName} on a {@code FABRIC} rule, say -- and its absence is what the
 * conversion validates against, so a malformed rule is rejected at the boundary rather than
 * producing a route that silently never matches.
 *
 * <p>{@code host} empty means the route matches any host, the same additive meaning the gateway's
 * own {@code HOST} config segment has. {@code prefix} carries the trailing {@code /*} spelling a
 * path may declare; {@code FABRIC} rules may never set it, since that route kind is permanently
 * exact-path-only.
 */
public record IngressRule(
    Optional<String> host,
    String path,
    boolean prefix,
    Kind kind,
    Optional<String> serviceName,
    Optional<String> deploymentName,
    Optional<String> portName,
    Optional<String> interfaceName,
    int majorVersion,
    Optional<String> methodName,
    Optional<String> paramType) {

  /** Which gateway route shape a rule becomes. */
  public enum Kind {
    FABRIC,
    VESSEL,
    SERVICE
  }

  public IngressRule {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("route path must not be blank");
    }
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException("route path must start with '/': " + path);
    }
    if (kind == null) {
      throw new IllegalArgumentException("route kind must not be null");
    }
    if (host == null
        || serviceName == null
        || deploymentName == null
        || portName == null
        || interfaceName == null
        || methodName == null
        || paramType == null) {
      throw new IllegalArgumentException(
          "optional route fields must be Optional.empty(), not null");
    }
    if (kind == Kind.FABRIC && prefix) {
      throw new IllegalArgumentException(
          "a FABRIC route is exact-path-only and must not declare a prefix: " + path);
    }
    switch (kind) {
      case SERVICE -> requirePresent(serviceName, "serviceName", kind);
      case VESSEL -> {
        requirePresent(deploymentName, "deploymentName", kind);
        requirePresent(portName, "portName", kind);
      }
      case FABRIC -> {
        requirePresent(interfaceName, "interfaceName", kind);
        requirePresent(methodName, "methodName", kind);
        requirePresent(paramType, "paramType", kind);
        if (majorVersion < 0) {
          throw new IllegalArgumentException("majorVersion must not be negative: " + majorVersion);
        }
      }
    }
  }

  private static void requirePresent(Optional<String> value, String field, Kind kind) {
    if (value.isEmpty() || value.get().isBlank()) {
      throw new IllegalArgumentException("a " + kind + " route requires " + field);
    }
  }

  /** A SERVICE route, the shape an Ingress most often declares. */
  public static IngressRule service(
      Optional<String> host, String path, boolean prefix, String serviceName) {
    return new IngressRule(
        host,
        path,
        prefix,
        Kind.SERVICE,
        Optional.of(serviceName),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.empty(),
        Optional.empty());
  }

  /** A VESSEL route, addressing one deployment's own named port. */
  public static IngressRule vessel(
      Optional<String> host, String path, boolean prefix, String deploymentName, String portName) {
    return new IngressRule(
        host,
        path,
        prefix,
        Kind.VESSEL,
        Optional.empty(),
        Optional.of(deploymentName),
        Optional.of(portName),
        Optional.empty(),
        0,
        Optional.empty(),
        Optional.empty());
  }

  /** A FABRIC route, invoking one method on a fabric service interface. */
  public static IngressRule fabric(
      Optional<String> host,
      String path,
      String interfaceName,
      int majorVersion,
      String methodName,
      String paramType) {
    return new IngressRule(
        host,
        path,
        false,
        Kind.FABRIC,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(interfaceName),
        majorVersion,
        Optional.of(methodName),
        Optional.of(paramType));
  }
}
