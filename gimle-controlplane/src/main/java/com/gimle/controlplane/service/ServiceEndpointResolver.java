package com.gimle.controlplane.service;

import com.gimle.core.protocol.InstanceObservation;
import com.gimle.core.protocol.NodeRegistration;
import com.gimle.mimir.manifest.ServiceSpec;
import com.gimle.mimir.store.InstanceAssignment;
import com.gimle.mimir.store.ObservedHeartbeat;
import com.gimle.mimir.store.StoreReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Computes a {@link ServiceSpec}'s current live endpoint set from the same assignment/heartbeat/
 * node-registration join {@code ApiServer}'s own {@code GET /endpoints/{name}} route already
 * performs -- shared here so both {@code ServiceReconciler} and {@code ApiServer}'s {@code GET
 * /services/{name}/endpoints} route compute the identical thing off the identical data, rather than
 * each growing its own copy. Unlike {@code /endpoints/{name}}, which lists every assigned instance
 * regardless of health, a Service endpoint set only ever includes an instance currently reporting
 * both alive and ready -- the same "only route to a healthy backend" posture Kubernetes' own
 * EndpointSlice controller has, and {@code DeploymentReconciler#isReady} already established
 * elsewhere in this codebase for a different purpose (gating rolling-update progress).
 */
public final class ServiceEndpointResolver {

  private ServiceEndpointResolver() {}

  public static ServiceEndpointResolution resolve(final StoreReader store, final ServiceSpec spec) {
    // An ExternalName Service resolves to exactly its declared external host -- there are no
    // backing instances to join against, and the host is a name the caller's own resolver (or the
    // OS) turns into an address, never one of this cluster's node hosts. With no instance to read
    // a port off, an undeclared targetPort can only mean the port callers already dial.
    if (spec.isExternalName()) {
      final int port = spec.targetPort().orElse(spec.port());
      return new ServiceEndpointResolution(
          List.of(new ServiceEndpoint(spec.externalName().orElseThrow(), port)), List.of());
    }
    final List<ServiceEndpoint> endpoints = new ArrayList<>();
    final List<String> exclusions = new ArrayList<>();
    for (final String deploymentName : spec.deploymentNames()) {
      for (final InstanceAssignment assignment :
          store.listAssignmentsFor(spec.tenantId(), deploymentName)) {
        final Optional<InstanceObservation> observation = readyObservation(store, assignment);
        if (observation.isEmpty()) {
          continue;
        }
        final OptionalInt port = selectPort(spec, observation.get());
        if (port.isEmpty()) {
          exclusions.add(exclusionReason(spec, assignment, observation.get()));
          continue;
        }
        resolveHost(store, assignment.nodeId())
            .ifPresent(
                host ->
                    endpoints.add(
                        new ServiceEndpoint(
                            host, port.getAsInt(), Optional.of(assignment.nodeId()))));
      }
    }
    return new ServiceEndpointResolution(endpoints, exclusions);
  }

  private static Optional<InstanceObservation> readyObservation(
      final StoreReader store, final InstanceAssignment assignment) {
    return store
        .getNodeHeartbeat(assignment.nodeId())
        .map(ObservedHeartbeat::heartbeat)
        .flatMap(
            heartbeat ->
                heartbeat.instances().stream()
                    .filter(
                        obs ->
                            obs.deploymentName().equals(assignment.deploymentName())
                                && obs.instanceIndex() == assignment.instanceIndex()
                                && obs.tenantId().equals(assignment.tenantId()))
                    .findFirst())
        .filter(InstanceObservation::alive)
        .filter(InstanceObservation::ready);
  }

  /**
   * A declared {@code targetPort} is authoritative: the instance contributes an endpoint on exactly
   * that port, or contributes nothing at all -- never some other port that happens to be the only
   * one it reports, which would silently send traffic somewhere the operator never named.
   *
   * <p>With no {@code targetPort} declared there is nothing to match against: {@link
   * InstanceObservation#ports()} is keyed by the name a port was declared under (e.g. {@code
   * "HTTP_PORT"}) and {@link ServiceSpec} carries no port-name selector -- unlike {@code
   * gimle-gateway}'s own {@code GatewayRoute#portName}, which exists precisely to resolve that same
   * ambiguity for its own routes. An instance reporting exactly one port is then unambiguous; one
   * reporting zero or several contributes no endpoint rather than a guess.
   */
  private static OptionalInt selectPort(
      final ServiceSpec spec, final InstanceObservation observation) {
    final Map<String, Integer> ports = observation.ports();
    if (spec.targetPort().isPresent()) {
      final int declared = spec.targetPort().getAsInt();
      return ports.containsValue(declared) ? OptionalInt.of(declared) : OptionalInt.empty();
    }
    return ports.size() == 1
        ? OptionalInt.of(ports.values().iterator().next())
        : OptionalInt.empty();
  }

  private static String exclusionReason(
      final ServiceSpec spec,
      final InstanceAssignment assignment,
      final InstanceObservation observation) {
    final String instance =
        assignment.deploymentName()
            + '/'
            + assignment.instanceIndex()
            + " on node "
            + assignment.nodeId();
    final String reported = new TreeSet<>(observation.ports().values()).toString();
    if (spec.targetPort().isPresent()) {
      return "service "
          + spec.name()
          + " declares targetPort "
          + spec.targetPort().getAsInt()
          + ", which instance "
          + instance
          + " does not report; it reports "
          + reported;
    }
    return "service "
        + spec.name()
        + " declares no targetPort, and instance "
        + instance
        + " reports "
        + observation.ports().size()
        + " ports "
        + reported
        + " -- exactly one is needed to pick without guessing";
  }

  private static Optional<String> resolveHost(final StoreReader store, final String nodeId) {
    return store
        .getNodeRegistration(nodeId)
        .flatMap(NodeRegistration::apiAddress)
        .map(ServiceEndpointResolver::hostOnly);
  }

  /** Strips a trailing {@code :port} off a registered {@code host:port} node address. */
  private static String hostOnly(final String hostPort) {
    final int at = hostPort.lastIndexOf(':');
    return at < 0 ? hostPort : hostPort.substring(0, at);
  }
}
